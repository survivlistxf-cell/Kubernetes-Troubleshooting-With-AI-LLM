package com.kdiag.server.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kdiag.server.metrics.MetricsCollector;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal Ollama HTTP client.
 *
 * Uses Ollama's native endpoint:
 * POST {baseUrl}/api/chat
 *
 * Supports both blocking (chat) and reactive streaming (chatStream).
 */
@Component
public class OllamaClient {

    private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);
    private final WebClient webClient;

    private final String model;
    private final double temperature;
    private final Duration timeout;

    // Field-injected so the 4-param constructor stays compatible with existing tests.
    // volatile + setter so it can be switched at runtime (e.g. 16384 <-> 32768) for benchmarking
    // without a restart. All prompt budgets in AiEngine derive from this value at request time.
    @Value("${ollama.num-ctx:32768}")
    private volatile int numCtx;

    // Tokens reserved for the generated answer, as a FRACTION of num_ctx so the answer room
    // scales with the window (a fixed reserve was ~12% at 16384 but only ~6% at 32768).
    @Value("${ollama.output-reserve-fraction:0.15}")
    private double outputReserveFraction;

    @Value("${ollama.chars-per-token:3.0}")
    private double charsPerToken;

    // Spring injects its shared ObjectMapper if available; falls back to a local instance otherwise.
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    // Optional metrics — null when running in a test context without the full application context.
    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    // -------------------------------------------------------------------------
    // Constructor (kept at 4 params for backward test compatibility)
    // -------------------------------------------------------------------------

    public OllamaClient(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:llama3.1}") String model,
            @Value("${ollama.temperature:0.2}") double temperature,
            @Value("${ollama.timeout-seconds:60}") long timeoutSeconds) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.model = model;
        this.temperature = temperature;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Exposes the configured context window so callers can detect prompt overflows. */
    public int getNumCtx() {
        return numCtx;
    }

    /**
     * Switches the context window at runtime (clamped to a sane range). Used for benchmarking
     * 16384 vs 32768 without a restart; all AiEngine budgets follow this value automatically.
     */
    public void setNumCtx(int value) {
        int clamped = Math.max(1024, Math.min(131072, value));
        logger.warn("num_ctx switched at runtime: {} -> {}", numCtx, clamped);
        this.numCtx = clamped;
    }

    /** Output reserve in TOKENS, derived from num_ctx and the reserve fraction. */
    public int getOutputTokenReserve() {
        return (int) (numCtx * outputReserveFraction);
    }

    public double getOutputReserveFraction() {
        return outputReserveFraction;
    }

    public double getCharsPerToken() {
        return charsPerToken;
    }

    /**
     * Char capacity available for the INPUT prompt: (num_ctx − output reserve) × chars/token.
     * The output reserve scales with num_ctx (outputReserveFraction). This is the single source
     * of truth from which all prompt sub-budgets are derived.
     */
    public int budgetInputChars() {
        int inputTokens = Math.max(1024, numCtx - getOutputTokenReserve());
        return (int) (inputTokens * charsPerToken);
    }

    /**
     * Queries {@code POST /api/show} for the model's maximum context length.
     *
     * <p>The {@code model_info} object in the response contains architecture-prefixed
     * keys such as {@code "llama.context_length"}, {@code "gemma.context_length"},
     * {@code "qwen.context_length"}, etc.  This method finds the first key that ends
     * with {@code ".context_length"} and returns its value.
     *
     * @return the model's max context length, or {@link Optional#empty()} if the
     *         endpoint is unavailable, the field is absent, or any error occurs.
     *         Failures are logged at DEBUG level — this check is informational only.
     */
    public Optional<Integer> queryModelMaxContext() {
        try {
            Map<String, Object> req = Map.of("name", model);
            String json = webClient.post()
                    .uri("/api/show")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (json == null || json.isBlank()) {
                logger.debug("queryModelMaxContext: empty response from /api/show");
                return Optional.empty();
            }

            ObjectMapper om = (objectMapper != null) ? objectMapper : new ObjectMapper();
            JsonNode root      = om.readTree(json);
            JsonNode modelInfo = root.get("model_info");
            if (modelInfo == null || !modelInfo.isObject()) {
                logger.debug("queryModelMaxContext: model_info absent in /api/show response");
                return extractNumCtxFromModelfile(root);
            }

            // Keys are architecture-dependent: "llama.context_length", "gemma.context_length", …
            var fields = modelInfo.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().endsWith(".context_length") && entry.getValue().isNumber()) {
                    int ctx = entry.getValue().asInt();
                    logger.debug("queryModelMaxContext: found {}: {}", entry.getKey(), ctx);
                    return Optional.of(ctx);
                }
            }

            logger.debug("queryModelMaxContext: no *.context_length key found in model_info; trying modelfile fallback");
            return extractNumCtxFromModelfile(root);
        } catch (Exception e) {
            logger.debug("queryModelMaxContext failed (non-fatal): {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Integer> extractNumCtxFromModelfile(JsonNode root) {
        JsonNode modelfileNode = root.get("modelfile");
        if (modelfileNode == null || !modelfileNode.isTextual()) {
            logger.debug("queryModelMaxContext: modelfile absent in /api/show response");
            return Optional.empty();
        }

        String modelfile = modelfileNode.asText();
        var matcher = java.util.regex.Pattern.compile("(?m)^PARAMETER\\s+num_ctx\\s+(\\d+)\\s*$").matcher(modelfile);
        if (matcher.find()) {
            int ctx = Integer.parseInt(matcher.group(1));
            logger.debug("queryModelMaxContext: using num_ctx from modelfile fallback: {}", ctx);
            return Optional.of(ctx);
        }

        logger.debug("queryModelMaxContext: no num_ctx parameter found in modelfile");
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Blocking chat (unchanged behaviour, new endpoint & request shape)
    // -------------------------------------------------------------------------

    public String chat(List<Map<String, String>> messages) {
        Map<String, Object> req = Map.of(
                "model",    model,
                "messages", messages,
                "stream",   false,
                "options",  Map.of("temperature", temperature, "num_ctx", numCtx));

        if (logger.isDebugEnabled()) {
            try {
                List<Map<String, String>> logMessages = messages.stream()
                        .map(m -> Map.of(
                                "role",    m.getOrDefault("role", "unknown"),
                                "content", truncate(m.getOrDefault("content", ""), 100)))
                        .toList();
                logger.debug("Request: model={} messages={}", model, logMessages);
            } catch (Exception ignored) {
            }
        }

        long ollamaStart = System.currentTimeMillis();
        OllamaNativeResponse resp = webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(OllamaNativeResponse.class)
                .timeout(timeout)
                .block();

        if (metricsCollector != null) {
            metricsCollector.recordOllamaLatency(System.currentTimeMillis() - ollamaStart);
        }

        if (resp == null || resp.message == null) {
            return null;
        }
        recordTokenUsage(resp);
        return resp.message.content;
    }

    // -------------------------------------------------------------------------
    // Reactive streaming chat
    // -------------------------------------------------------------------------

    /**
     * Streams token strings from Ollama's NDJSON streaming response.
     * Each emitted element is the raw {@code message.content} of one token chunk.
     * The terminal {@code done:true} object is filtered out.
     */
    public Flux<String> chatStream(List<Map<String, String>> messages) {
        Map<String, Object> req = Map.of(
                "model",    model,
                "messages", messages,
                "stream",   true,
                "options",  Map.of("temperature", temperature, "num_ctx", numCtx));

        ObjectMapper om = (objectMapper != null) ? objectMapper : new ObjectMapper();

        final long streamStart = System.currentTimeMillis();
        // Flag ensures we record the time-to-first-token only once
        final AtomicBoolean firstTokenRecorded = new AtomicBoolean(false);

        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout)
                // Ollama may batch multiple JSON objects in one TCP frame separated by '\n'
                .flatMapIterable(chunk -> Arrays.asList(chunk.split("\n")))
                .filter(line -> !line.isBlank())
                .flatMap(line -> {
                    try {
                        OllamaNativeResponse resp = om.readValue(line, OllamaNativeResponse.class);
                        if (resp.done) {
                            recordTokenUsage(resp); // terminal object carries the token counts
                            return Flux.empty();
                        }
                        if (resp.message == null || resp.message.content == null
                                || resp.message.content.isEmpty()) {
                            return Flux.empty();
                        }
                        return Flux.just(resp.message.content);
                    } catch (Exception e) {
                        // Skip malformed / non-JSON lines (e.g. keep-alive pings)
                        return Flux.empty();
                    }
                })
                // Record time-to-first-token once the first real content chunk is emitted
                .doOnNext(token -> {
                    if (metricsCollector != null && firstTokenRecorded.compareAndSet(false, true)) {
                        metricsCollector.recordOllamaLatency(System.currentTimeMillis() - streamStart);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Response model (Ollama native /api/chat format)
    // -------------------------------------------------------------------------

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class OllamaNativeResponse {
        public OllamaMessage message;
        public boolean done;
        // Present on the terminal done:true object — the ground-truth token counts.
        @com.fasterxml.jackson.annotation.JsonProperty("prompt_eval_count")
        public Integer promptEvalCount;
        @com.fasterxml.jackson.annotation.JsonProperty("eval_count")
        public Integer evalCount;
    }

    public static class OllamaMessage {
        public String role;
        public String content;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Logs and records the ground-truth token usage reported by Ollama on the done object.
     * prompt_eval_count = input tokens, eval_count = generated tokens. Their sum vs num_ctx
     * is the real overflow check (replaces the chars*4 heuristic).
     */
    private void recordTokenUsage(OllamaNativeResponse resp) {
        if (resp == null || resp.promptEvalCount == null) return;
        int promptTokens = resp.promptEvalCount;
        int evalTokens = resp.evalCount != null ? resp.evalCount : 0;
        logger.info("Ollama tokens: prompt={} eval={} total={} / num_ctx={}",
                promptTokens, evalTokens, promptTokens + evalTokens, numCtx);
        if (metricsCollector != null) {
            metricsCollector.recordTokenUsage(promptTokens, evalTokens, numCtx);
        }
    }
}
