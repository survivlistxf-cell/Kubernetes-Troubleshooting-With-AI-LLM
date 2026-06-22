package com.kdiag.server.ai.feedback;

import com.kdiag.server.entities.QaFeedback;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.llm.OllamaEmbeddingClient;
import com.kdiag.server.repositories.ProblemResolutionRepository;
import com.kdiag.server.repositories.QaFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Manages the full read+write lifecycle of feedback-driven case-based retrieval.
 *
 * <p><b>Write side (Prompt 1):</b>
 * <ol>
 *   <li>Record every (question, answer) exchange for later feedback attachment.</li>
 *   <li>On positive feedback: embed the user question and persist the vector so
 *       the row becomes a "known-good" case for future ANN lookups.</li>
 *   <li>On negative feedback: mark the row with feedback=-1.</li>
 * </ol>
 *
 * <p><b>Read side (Prompt 2):</b>
 * <ol>
 *   <li>{@link #findSimilarCases} — ANN search over liked Q&A pairs, returns
 *       cases above the cosine-similarity threshold for injection into the system prompt.</li>
 *   <li>{@link #getBoostedUrls} — TTL-cached set of URLs associated with liked
 *       dynamic-search resolutions, used to post-rank BM25 chunks.</li>
 * </ol>
 *
 * <p>All embedding I/O is best-effort; any failure logs a WARN but never propagates.
 */
@Service
public class FeedbackRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackRetrievalService.class);

    // -------------------------------------------------------------------------
    // Read-path constants
    // -------------------------------------------------------------------------
    private static final double SIMILARITY_THRESHOLD   = 0.75;   // cosine similarity (NOT distance)
    private static final int    MAX_SIMILAR_CASES       = 3;
    private static final long   BOOSTED_URLS_TTL_MS     = 60_000L;
    private static final int    MAX_CASE_RESPONSE_CHARS = 1200;
    private static final int    MAX_CASE_QUESTION_CHARS = 300;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------
    private final OllamaEmbeddingClient       embeddingClient;
    private final QaFeedbackRepository        qaRepo;
    private final ProblemResolutionRepository resolutionRepository;
    private final MetricsCollector            metrics;

    // -------------------------------------------------------------------------
    // Startup probe — warn once when the embedding model is not available
    // -------------------------------------------------------------------------
    private volatile boolean embeddingUnavailableWarned = false;

    // -------------------------------------------------------------------------
    // Boosted-URL cache
    // -------------------------------------------------------------------------
    private volatile Set<String> cachedBoostedUrls   = Set.of();
    private volatile long        cachedBoostedUrlsAt = 0L;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    public FeedbackRetrievalService(OllamaEmbeddingClient embeddingClient,
                                    QaFeedbackRepository qaRepo,
                                    ProblemResolutionRepository resolutionRepository,
                                    MetricsCollector metrics) {
        this.embeddingClient      = embeddingClient;
        this.qaRepo               = qaRepo;
        this.resolutionRepository = resolutionRepository;
        this.metrics              = metrics;
    }

    // -------------------------------------------------------------------------
    // SimilarCase value type
    // -------------------------------------------------------------------------

    /** A liked Q&A pair from the qa_feedback table, retrieved by ANN similarity. */
    public record SimilarCase(long id, String userQuestion, String aiResponse, double similarity,
                              List<String> sourceUrls) {}

    // =========================================================================
    // WRITE SIDE
    // =========================================================================

    /**
     * Persists a new {@link QaFeedback} row with {@code feedback=0} and no embedding.
     * The embedding is computed lazily when (and only if) the user likes the answer.
     *
     * @return the saved entity, or {@code null} if the input is incomplete
     */
    public QaFeedback recordExchange(String conversationId,
                                     String userQuestion,
                                     String aiResponse,
                                     String sourceUrls) {
        if (conversationId == null || conversationId.isBlank()
                || userQuestion == null || userQuestion.isBlank()
                || aiResponse   == null || aiResponse.isBlank()) {
            logger.debug("recordExchange: skipping incomplete exchange for conv={}", conversationId);
            return null;
        }
        QaFeedback row   = new QaFeedback(conversationId, userQuestion, aiResponse, sourceUrls);
        QaFeedback saved = qaRepo.save(row);
        logger.info("Recorded QA exchange for conv {} (id {})", conversationId, saved.getId());
        return saved;
    }

    /**
     * Called when the user clicks 👍.
     * Embeds the question (if not already done) and flips the row to feedback=1.
     */
    public void onPositiveFeedback(String conversationId) {
        Optional<QaFeedback> opt = qaRepo.findTopByConversationIdOrderByCreatedAtDesc(conversationId);
        if (opt.isEmpty()) {
            logger.warn("onPositiveFeedback: no QA exchange found for conv={}", conversationId);
            return;
        }
        QaFeedback row = opt.get();

        if (row.getEmbedding() != null) {
            qaRepo.updateFeedbackOnly(row.getId(), 1);
            logger.info("Positive feedback re-applied to already-embedded case (id {})", row.getId());
            // Invalidate boosted-URL cache so the next getBoostedUrls() sees the change
            cachedBoostedUrlsAt = 0L;
            return;
        }

        String vec = embeddingClient.embedAsPgVector(row.getUserQuestion());
        if (vec == null) {
            logger.warn("Embedding failed for conv={} (id {}); marking feedback without embedding",
                    conversationId, row.getId());
            qaRepo.updateFeedbackOnly(row.getId(), 1);
        } else {
            qaRepo.updateEmbeddingAndFeedback(row.getId(), vec, 1);
            logger.info("Captured liked case (id {}, {} chars question)",
                    row.getId(), row.getUserQuestion().length());
        }
        // Invalidate boosted-URL cache
        cachedBoostedUrlsAt = 0L;
    }

    /**
     * Called when the user clicks 👎.
     * Marks the row with feedback=-1; no embedding is computed.
     */
    public void onNegativeFeedback(String conversationId) {
        Optional<QaFeedback> opt = qaRepo.findTopByConversationIdOrderByCreatedAtDesc(conversationId);
        if (opt.isEmpty()) {
            logger.warn("onNegativeFeedback: no QA exchange found for conv={}", conversationId);
            return;
        }
        qaRepo.updateFeedbackOnly(opt.get().getId(), -1);
        logger.info("Negative feedback recorded for conv={} (id {})", conversationId, opt.get().getId());
    }

    // =========================================================================
    // READ SIDE
    // =========================================================================

    /**
     * Finds liked Q&A cases whose embedded user question is semantically similar
     * to {@code userQuestion} (cosine similarity ≥ {@value #SIMILARITY_THRESHOLD}).
     *
     * <p>Returns an empty list on any failure — this must never block the chat path.
     */
    public List<SimilarCase> findSimilarCases(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) return List.of();
        try {
            String vec = embeddingClient.embedAsPgVector(userQuestion);
            if (vec == null) {
                // Warn once so operators notice the model is missing
                if (!embeddingUnavailableWarned) {
                    embeddingUnavailableWarned = true;
                    logger.warn("Embedding model unavailable ({}). Case-based retrieval is OFF; " +
                                    "run `ollama pull {}` to enable.",
                            embeddingClient.getEmbeddingModel(),
                            embeddingClient.getEmbeddingModel());
                }
                return List.of();
            }
            // Successful embedding — reset the warning flag so it re-fires if the model later disappears
            embeddingUnavailableWarned = false;

            List<Object[]> rows = qaRepo.findSimilarByEmbeddingWithDistance(vec, MAX_SIMILAR_CASES);
            List<SimilarCase> out = new ArrayList<>();
            for (Object[] r : rows) {
                // Column order per findSimilarByEmbeddingWithDistance:
                // 0=id, 1=conversation_id, 2=user_question, 3=ai_response,
                // 4=emb_text, 5=feedback, 6=source_urls, 7=created_at, 8=distance
                long   id         = ((Number) r[0]).longValue();
                String question   = String.valueOf(r[2]);
                String response   = String.valueOf(r[3]);
                double distance   = ((Number) r[8]).doubleValue();  // 0=identical, 1=orthogonal
                double similarity = 1.0 - distance;
                if (similarity < SIMILARITY_THRESHOLD) continue;
                Object rawSourceUrls = r[6];
                out.add(new SimilarCase(id,
                        truncate(question, MAX_CASE_QUESTION_CHARS),
                        truncate(response, MAX_CASE_RESPONSE_CHARS),
                    similarity,
                    parseSourceUrls(rawSourceUrls == null ? null : rawSourceUrls.toString())));
            }
            logger.info("findSimilarCases: returned {} hits >= {} for question ({} chars)",
                    out.size(), SIMILARITY_THRESHOLD, userQuestion.length());
            metrics.recordSimilarCasesQuery(out.size());
            return out;
        } catch (Exception e) {
            logger.warn("findSimilarCases failed: {}", e.getMessage());
            metrics.recordSimilarCasesQuery(0);
            return List.of();
        }
    }

    /**
     * Returns the set of URLs from positively-rated dynamic-search resolutions,
     * used to post-boost BM25 chunk scoring.
     *
     * <p>Reloaded from Postgres at most once per {@value #BOOSTED_URLS_TTL_MS} ms.
     * On failure, returns the stale cached value (which may be empty on first load).
     */
    public Set<String> getBoostedUrls() {
        long now = System.currentTimeMillis();
        if (now - cachedBoostedUrlsAt < BOOSTED_URLS_TTL_MS) return cachedBoostedUrls;
        try {
            List<String> rows = resolutionRepository.findAllUsefulUrlsWithPositiveFeedback();
            Set<String> out = new HashSet<>();
            for (String row : rows) {
                if (row == null) continue;
                for (String u : row.split("\\R")) {   // any line break: \n, \r\n, etc.
                    String trimmed = u.trim();
                    if (!trimmed.isEmpty() && trimmed.startsWith("http")) out.add(trimmed);
                }
            }
            cachedBoostedUrls   = Set.copyOf(out);
            cachedBoostedUrlsAt = now;
            logger.info("Reloaded boosted-URL cache: {} URLs", cachedBoostedUrls.size());
            return cachedBoostedUrls;
        } catch (Exception e) {
            logger.warn("getBoostedUrls failed, returning stale cache: {}", e.getMessage());
            return cachedBoostedUrls;
        }
    }

    /** Exposes cache metadata for the /v1/feedback/boosted-urls debug endpoint. */
    public long getCachedBoostedUrlsAt() {
        return cachedBoostedUrlsAt;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    static List<String> parseSourceUrls(String sourceUrls) {
        try {
            if (sourceUrls == null || sourceUrls.isBlank()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String url : sourceUrls.split("\\R")) {
                String trimmed = url.trim();
                if (trimmed.startsWith("http")) {
                    out.add(trimmed);
                    if (out.size() >= 5) {
                        break;
                    }
                }
            }
            return out.isEmpty() ? List.of() : List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }
}
