package com.kdiag.server.api;

import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ollama.OllamaEmbeddingClient;
import com.kdiag.server.repositories.QaFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observability endpoints for the feedback-driven CBR subsystem.
 */
@RestController
@RequestMapping("/v1/feedback")
public class FeedbackController {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackController.class);

    private static final int EMBEDDING_DIMENSION   = 768;
    private static final long BOOSTED_URLS_TTL_MS  = 60_000L;

    private final QaFeedbackRepository     qaRepo;
    private final OllamaEmbeddingClient    embeddingClient;
    private final FeedbackRetrievalService feedbackRetrievalService;

    public FeedbackController(QaFeedbackRepository qaRepo,
                              OllamaEmbeddingClient embeddingClient,
                              FeedbackRetrievalService feedbackRetrievalService) {
        this.qaRepo                  = qaRepo;
        this.embeddingClient         = embeddingClient;
        this.feedbackRetrievalService = feedbackRetrievalService;
    }

    /**
     * Returns a health/stats snapshot of the qa_feedback table and embedding config.
     *
     * <p>Example response:
     * <pre>
     * {
     *   "totalRecorded":  42,
     *   "positive":        7,
     *   "negative":        2,
     *   "neutral":        33,
     *   "withEmbedding":   7,
     *   "embeddingModel": "nomic-embed-text",
     *   "embeddingDimension": 768
     * }
     * </pre>
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("totalRecorded",      qaRepo.count());
            result.put("positive",           qaRepo.countByFeedback(1));
            result.put("negative",           qaRepo.countByFeedback(-1));
            result.put("neutral",            qaRepo.countByFeedback(0));
            result.put("withEmbedding",      qaRepo.countWithEmbedding());
            result.put("embeddingModel",     embeddingClient.getEmbeddingModel());
            result.put("embeddingDimension", EMBEDDING_DIMENSION);
        } catch (Exception e) {
            logger.error("Failed to compute feedback stats", e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Debug endpoint — shows the current state of the boosted-URL TTL cache.
     *
     * <p>Example response:
     * <pre>
     * {
     *   "count": 5,
     *   "ttlSecondsRemaining": 42,
     *   "urls": ["https://kubernetes.io/...", ...]
     * }
     * </pre>
     *
     * <p>Calling this endpoint also triggers a cache refresh if the TTL has expired.
     */
    @GetMapping("/boosted-urls")
    public Map<String, Object> boostedUrls() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Set<String> urls   = feedbackRetrievalService.getBoostedUrls();
            long cachedAt      = feedbackRetrievalService.getCachedBoostedUrlsAt();
            long ageMs         = System.currentTimeMillis() - cachedAt;
            long ttlSec        = Math.max(0L, (BOOSTED_URLS_TTL_MS - ageMs) / 1000L);

            // Return at most 20 URLs, sorted for stable output
            List<String> sample = new ArrayList<>(urls);
            Collections.sort(sample);
            if (sample.size() > 20) sample = sample.subList(0, 20);

            result.put("count",             urls.size());
            result.put("ttlSecondsRemaining", ttlSec);
            result.put("urls",              sample);
        } catch (Exception e) {
            logger.error("Failed to compute boosted-URLs info", e);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
