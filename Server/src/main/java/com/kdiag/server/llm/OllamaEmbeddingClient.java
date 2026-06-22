package com.kdiag.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kdiag.server.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Calls Ollama's {@code POST /api/embeddings} to produce dense vector embeddings.
 *
 * <p>All I/O is best-effort: any failure (timeout, HTTP error, JSON parse error)
 * logs a WARN and returns {@code null} — it must never propagate an exception to
 * the caller.
 *
 * <p>Uses a dedicated {@link WebClient} so it does not share state with
 * {@link GptChatClient}.
 */
@Component
public class OllamaEmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final WebClient webClient;
    private final String embeddingModel;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Optional — null when running in a test context without the full application context.
    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    public OllamaEmbeddingClient(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.embedding-model:nomic-embed-text}") String embeddingModel,
            @Value("${ollama.embedding-timeout-seconds:30}") long timeoutSeconds) {
        this.webClient     = WebClient.builder().baseUrl(baseUrl).build();
        this.embeddingModel = embeddingModel;
        this.timeout       = Duration.ofSeconds(timeoutSeconds);
        logger.info("OllamaEmbeddingClient initialised: model={} baseUrl={}", embeddingModel, baseUrl);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Embeds {@code text} and returns a {@code float[]} of length 768, or
     * {@code null} on any failure.
     *
     * <p>Records embedding latency on success and an embedding-failure counter on
     * any failure path (null body, missing array, or exception).
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            logger.debug("embed() called with blank text — returning null");
            return null;
        }

        final long embStart = System.currentTimeMillis();
        float[] result = null;

        try {
            Map<String, Object> req = Map.of("model", embeddingModel, "prompt", text);

            String json = webClient.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();

            if (json == null || json.isBlank()) {
                logger.warn("Ollama embedding API returned empty body for model={}", embeddingModel);
                // result stays null → failure path below
            } else {
                JsonNode root = objectMapper.readTree(json);
                JsonNode embNode = root.get("embedding");
                if (embNode == null || !embNode.isArray() || embNode.isEmpty()) {
                    logger.warn("Ollama embedding response missing 'embedding' array: {}",
                            json.substring(0, Math.min(200, json.length())));
                    // result stays null → failure path below
                } else {
                    float[] r = new float[embNode.size()];
                    for (int i = 0; i < embNode.size(); i++) {
                        r[i] = (float) embNode.get(i).asDouble();
                    }
                    logger.debug("Embedded {} chars → {} dims", text.length(), r.length);
                    result = r;
                }
            }
        } catch (Exception e) {
            logger.warn("OllamaEmbeddingClient.embed() failed: {}", e.getMessage());
            // result stays null → failure path below
        }

        // Record metrics — single point for both success and failure
        if (metricsCollector != null) {
            if (result != null) {
                metricsCollector.recordEmbeddingLatency(System.currentTimeMillis() - embStart);
            } else {
                metricsCollector.recordEmbeddingFailure();
            }
        }

        return result;
    }

    /**
     * Embeds {@code text} and formats the result as a pgvector text literal
     * {@code "[d0,d1,...,dN]"} with up to 6 decimal places per component.
     *
     * @return the formatted literal, or {@code null} if embedding failed
     */
    public String embedAsPgVector(String text) {
        float[] vec = embed(text);
        if (vec == null) return null;

        StringBuilder sb = new StringBuilder(vec.length * 12);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            // Locale.ROOT ensures '.' as decimal separator regardless of JVM locale
            sb.append(String.format(Locale.ROOT, "%.6f", vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }
}
