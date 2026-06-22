package com.kdiag.server.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kdiag.server.metrics.MetricsCollector;
import reactor.core.publisher.Flux;

import java.time.Duration;
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
 * Chat client speaking the <b>OpenAI-compatible</b> protocol (gpt-oss / vLLM):
 *
 * <pre>POST {baseUrl}/chat/completions</pre>
 *
 * where {@code baseUrl} already ends in {@code /v1}. Authenticates with a Bearer
 * token when {@code llm.chat.api-key} is set.
 *
 * <p>Supports both blocking ({@link #chat}) and reactive streaming
 * ({@link #chatStream}) modes. Streaming uses SSE ({@code data:} frames terminated
 * by {@code [DONE]}), not Ollama's NDJSON.
 *
 * <p>The {@code num_ctx} / prompt-budget machinery below is kept because
 * {@code AiEngine}/{@code BudgetComputing} use it to size the prompt locally.
 * gpt-oss manages its own context window, so {@code num_ctx} is no longer sent to
 * the server — but the local char budget derived from it stays valid.
 */
@Component
public class GptChatClient {

    private static final Logger logger = LoggerFactory.getLogger(GptChatClient.class);
    private final WebClient webClient;

    private final String model;
    private final double temperature;
    private final Duration timeout;
    // 0 = do not send max_tokens (let the server decide); >0 caps the generated answer.
    private final int maxOutputTokens;

    // Field-injected so the constructor stays focused on the chat client wiring.
    // volatile + setter so it can be switched at runtime (e.g. 16384 <-> 32768) for benchmarking
    // without a restart. All prompt budgets in AiEngine derive from this value at request time.
    @Value("${llm.chat.num-ctx:128000}")
    private volatile int numCtx;

    // Tokens reserved for the generated answer, as a FRACTION of num_ctx so the answer room
    // scales with the window (a fixed reserve was ~12% at 16384 but only ~6% at 32768).
    @Value("${llm.chat.output-reserve-fraction:0.15}")
    private double outputReserveFraction;

    @Value("${llm.chat.chars-per-token:3.0}")
    private double charsPerToken;

    // Spring injects its shared ObjectMapper if available; falls back to a local instance otherwise.
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    // Optional metrics — null when running in a test context without the full application context.
    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    // -------------------------------------------------------------------------
    // Constructor (OpenAI-compatible chat client)
    // -------------------------------------------------------------------------

    public GptChatClient(
            @Value("${llm.chat.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${llm.chat.model:openai/gpt-oss-120b}") String model,
            @Value("${llm.chat.api-key:}") String apiKey,
            @Value("${llm.chat.temperature:0.2}") double temperature,
            @Value("${llm.chat.timeout-seconds:300}") long timeoutSeconds,
            @Value("${llm.chat.max-output-tokens:0}") int maxOutputTokens) {

        WebClient.Builder b = WebClient.builder()
                .baseUrl(baseUrl)
                // raise the buffer: responses can exceed the 256KB default
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (apiKey != null && !apiKey.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + apiKey.trim());
        }
        this.webClient = b.build();
        this.model = model;
        this.temperature = temperature;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.maxOutputTokens = maxOutputTokens;
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
     * The OpenAI endpoint does not expose the model's context length in a standard
     * way, so we skip the check and let {@link GptStartupCheck} log a graceful WARN.
     *
     * @return always {@link Optional#empty()}.
     */
    public Optional<Integer> queryModelMaxContext() {
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Blocking chat (OpenAI /chat/completions)
    // -------------------------------------------------------------------------

    public String chat(List<Map<String, String>> messages) {
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("model", model);
        req.put("messages", messages);
        req.put("stream", false);
        req.put("temperature", temperature);
        if (maxOutputTokens > 0) req.put("max_tokens", maxOutputTokens);

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

        long start = System.currentTimeMillis();
        OpenAiResponse resp = webClient.post()
                .uri("/chat/completions")               // baseUrl already contains /v1
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(OpenAiResponse.class)
                .timeout(timeout)
                .block();

        if (metricsCollector != null) {
            metricsCollector.recordChatLatency(System.currentTimeMillis() - start);
        }

        if (resp == null || resp.choices == null || resp.choices.isEmpty()) {
            return null;
        }
        recordTokenUsage(resp);
        return resp.choices.get(0).message != null ? resp.choices.get(0).message.content : null;
    }

    // -------------------------------------------------------------------------
    // Reactive streaming chat (OpenAI SSE)
    // -------------------------------------------------------------------------

    /**
     * Streams token strings from the OpenAI SSE response. Each event payload is a JSON
     * chunk whose {@code choices[0].delta.content} carries one token fragment; the stream
     * terminates with a literal {@code [DONE]} sentinel which is filtered out.
     */
    public Flux<String> chatStream(List<Map<String, String>> messages) {
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("model", model);
        req.put("messages", messages);
        req.put("stream", true);
        req.put("temperature", temperature);
        if (maxOutputTokens > 0) req.put("max_tokens", maxOutputTokens);
        // ask for usage on the stream too (vLLM/OpenAI): it arrives in the final chunk
        req.put("stream_options", Map.of("include_usage", true));

        ObjectMapper om = (objectMapper != null) ? objectMapper : new ObjectMapper();

        final long streamStart = System.currentTimeMillis();
        // Flag ensures we record the time-to-first-token only once
        final AtomicBoolean firstTokenRecorded = new AtomicBoolean(false);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(String.class)          // each element = payload after "data: "
                .timeout(timeout)
                .filter(line -> line != null && !line.isBlank())
                .takeUntil(line -> line.trim().equals("[DONE]"))
                .flatMap(line -> {
                    // WebFlux usually strips the "data:" prefix on TEXT_EVENT_STREAM; handle both.
                    String json = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
                    if (json.equals("[DONE]") || json.isEmpty()) return Flux.empty();
                    try {
                        OpenAiStreamChunk chunk = om.readValue(json, OpenAiStreamChunk.class);
                        if (chunk.usage != null) recordTokenUsage(chunk.usage);
                        if (chunk.choices == null || chunk.choices.isEmpty()) return Flux.empty();
                        var delta = chunk.choices.get(0).delta;
                        if (delta == null || delta.content == null || delta.content.isEmpty()) {
                            return Flux.empty();
                        }
                        return Flux.just(delta.content);
                    } catch (Exception e) {
                        // Skip pings / non-JSON keep-alive lines
                        return Flux.empty();
                    }
                })
                // Record time-to-first-token once the first real content chunk is emitted
                .doOnNext(token -> {
                    if (metricsCollector != null && firstTokenRecorded.compareAndSet(false, true)) {
                        metricsCollector.recordChatLatency(System.currentTimeMillis() - streamStart);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Response model (OpenAI /chat/completions format)
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAiResponse {
        public List<Choice> choices;
        public Usage usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAiStreamChunk {
        public List<StreamChoice> choices;
        public Usage usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        public Msg message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StreamChoice {
        public Delta delta;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Msg {
        public String role;
        public String content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delta {
        public String role;
        public String content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        public Integer promptTokens;
        @JsonProperty("completion_tokens")
        public Integer completionTokens;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Logs and records the ground-truth token usage reported by the server.
     * prompt_tokens = input tokens, completion_tokens = generated tokens. Their sum vs
     * num_ctx (local budget) is the real overflow check (replaces the chars*4 heuristic).
     */
    private void recordTokenUsage(OpenAiResponse resp) {
        if (resp != null) recordTokenUsage(resp.usage);
    }

    private void recordTokenUsage(Usage usage) {
        if (usage == null || usage.promptTokens == null) return;
        int promptTokens = usage.promptTokens;
        int evalTokens = usage.completionTokens != null ? usage.completionTokens : 0;
        logger.info("LLM tokens: prompt={} eval={} total={} / num_ctx(local)={}",
                promptTokens, evalTokens, promptTokens + evalTokens, numCtx);
        if (metricsCollector != null) {
            metricsCollector.recordTokenUsage(promptTokens, evalTokens, numCtx);
        }
    }
}
