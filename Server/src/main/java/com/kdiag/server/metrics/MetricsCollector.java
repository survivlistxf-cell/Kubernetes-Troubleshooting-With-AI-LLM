package com.kdiag.server.metrics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process, thread-safe metrics collector for the Kubexplain AI Server.
 *
 * <p>All counters are {@link AtomicLong} so recording is lock-free on the hot path.
 * The {@link #snapshot()} method assembles a stable {@link LinkedHashMap} (same key
 * order on every call) with raw counters and derived averages/rates suitable for the
 * thesis demo.
 *
 * <p>No Micrometer or Spring Actuator dependency is required.
 */
@Component
public class MetricsCollector {

    // =========================================================================
    // Chat throughput
    // =========================================================================
    private final AtomicLong totalChatRequests        = new AtomicLong();
    private final AtomicLong totalStreamingRequests   = new AtomicLong();
    private final AtomicLong totalFallbackResponses   = new AtomicLong();
    private final AtomicLong totalNeedsSearchTriggers = new AtomicLong();

    // =========================================================================
    // Latency sums (ms) — divide by the corresponding request count for averages
    // =========================================================================
    private final AtomicLong totalResponseTimeMs    = new AtomicLong();
    private final AtomicLong totalOllamaLatencyMs   = new AtomicLong();
    private final AtomicLong totalEmbeddingLatencyMs = new AtomicLong();

    // =========================================================================
    // Prompt / response size (chars; divide by 4 for approximate tokens)
    // =========================================================================
    private final AtomicLong totalPromptChars   = new AtomicLong();
    private final AtomicLong totalResponseChars = new AtomicLong();

    // =========================================================================
    // BM25 retrieval
    // =========================================================================
    private final AtomicLong bm25Searches        = new AtomicLong();
    private final AtomicLong bm25EmptyResults    = new AtomicLong();
    private final AtomicLong bm25BoostedSearches = new AtomicLong();

    // =========================================================================
    // Semantic / feedback retrieval
    // =========================================================================
    private final AtomicLong similarCasesQueries = new AtomicLong();
    private final AtomicLong similarCasesHits    = new AtomicLong();
    private final AtomicLong embeddingFailures   = new AtomicLong();

    // =========================================================================
    // Ollama context budget
    // =========================================================================
    /** Approximate count of requests where total prompt chars > numCtx * 4. */
    private final AtomicLong numCtxOverflowsApprox = new AtomicLong();

    // Ground-truth token usage reported by Ollama (prompt_eval_count / eval_count).
    private final AtomicLong lastPromptTokens   = new AtomicLong();
    private final AtomicLong lastEvalTokens      = new AtomicLong();
    private final AtomicLong maxPromptTokens      = new AtomicLong();
    private final AtomicLong tokenSamples         = new AtomicLong();
    private final AtomicLong sumPromptTokens       = new AtomicLong();
    /** Count of requests where measured prompt+eval tokens reached/exceeded num_ctx. */
    private final AtomicLong numCtxOverflowsReal   = new AtomicLong();

    // =========================================================================
    // Retrieval engine comparison (lucene vs elastic)
    // =========================================================================
    private final AtomicLong luceneRetrievalCalls    = new AtomicLong();
    private final AtomicLong luceneRetrievalHits     = new AtomicLong();
    private final AtomicLong luceneRetrievalTotalMs  = new AtomicLong();
    private final AtomicLong elasticRetrievalCalls   = new AtomicLong();
    private final AtomicLong elasticRetrievalHits    = new AtomicLong();
    private final AtomicLong elasticRetrievalTotalMs = new AtomicLong();

    // =========================================================================
    // Dynamic-page cleanup
    // =========================================================================
    private final AtomicLong cleanupRunsTotal      = new AtomicLong();
    private final AtomicLong cleanupPagesDeleted   = new AtomicLong();
    private final AtomicLong cleanupLastDurationMs = new AtomicLong();
    private volatile Instant  cleanupLastRunAt;
    private volatile Boolean  cleanupLastRunDryRun;

    // =========================================================================
    // Recording methods — allocation-free on the hot path
    // =========================================================================

    /** Called once per completed non-streaming chat request. */
    public void recordChatRequest(long elapsedMs, int promptChars, int responseChars) {
        totalChatRequests.incrementAndGet();
        totalResponseTimeMs.addAndGet(elapsedMs);
        totalPromptChars.addAndGet(promptChars);
        totalResponseChars.addAndGet(responseChars);
    }

    /** Called once per completed streaming chat request (in doFinally). */
    public void recordStreamingRequest(long elapsedMs, int promptChars, int responseChars) {
        totalStreamingRequests.incrementAndGet();
        totalResponseTimeMs.addAndGet(elapsedMs);
        totalPromptChars.addAndGet(promptChars);
        totalResponseChars.addAndGet(responseChars);
    }

    /** Called when Ollama returned null/blank and the local fallback was used. */
    public void recordFallbackResponse() {
        totalFallbackResponses.incrementAndGet();
    }

    /** Called each time a {@code [NEEDS_SEARCH:]} trigger is detected. */
    public void recordNeedsSearchTrigger() {
        totalNeedsSearchTriggers.incrementAndGet();
    }

    /** Called with the wall-clock ms of a completed Ollama chat call (blocking or first-chunk). */
    public void recordOllamaLatency(long ms) {
        totalOllamaLatencyMs.addAndGet(ms);
    }

    /** Called with the wall-clock ms of a successful embedding call. */
    public void recordEmbeddingLatency(long ms) {
        totalEmbeddingLatencyMs.addAndGet(ms);
    }

    /** Called when {@code embedAsPgVector} returns null (model absent or HTTP failure). */
    public void recordEmbeddingFailure() {
        embeddingFailures.incrementAndGet();
    }

    /**
     * Called once per BM25 search execution.
     *
     * @param empty   {@code true} when the result set is empty
     * @param boosted {@code true} when called with a non-empty {@code boostedUrls} set
     */
    public void recordBm25Search(boolean empty, boolean boosted) {
        bm25Searches.incrementAndGet();
        if (empty)   bm25EmptyResults.incrementAndGet();
        if (boosted) bm25BoostedSearches.incrementAndGet();
    }

    /**
     * Called once per {@code findSimilarCases} invocation.
     *
     * @param hits number of cases returned (0 means no useful prior cases found)
     */
    public void recordSimilarCasesQuery(int hits) {
        similarCasesQueries.incrementAndGet();
        if (hits > 0) similarCasesHits.incrementAndGet();
    }

    /**
     * Increments the overflow counter when the assembled prompt is longer than the
     * model's context window (approximated as {@code numCtxTokens * 4} chars).
     */
    public void recordNumCtxOverflowIfApplicable(int promptChars, int numCtxTokens) {
        if (promptChars > (long) numCtxTokens * 4) {
            numCtxOverflowsApprox.incrementAndGet();
        }
    }

    /**
     * Records ground-truth token usage reported by Ollama (prompt_eval_count / eval_count).
     * This is the real measurement that replaces the chars*4 estimate: if prompt+eval reaches
     * num_ctx, the window genuinely overflowed and Ollama truncated the prompt.
     */
    public void recordTokenUsage(int promptTokens, int evalTokens, int numCtx) {
        lastPromptTokens.set(promptTokens);
        lastEvalTokens.set(evalTokens);
        maxPromptTokens.accumulateAndGet(promptTokens, Math::max);
        sumPromptTokens.addAndGet(promptTokens);
        tokenSamples.incrementAndGet();
        if (promptTokens + evalTokens >= numCtx) {
            numCtxOverflowsReal.incrementAndGet();
        }
    }

    /**
     * Called after each retrieval search to track latency and hit-rate per engine.
     *
     * @param engine one of {@code "lucene"} or {@code "elastic"}
     * @param ms     wall-clock milliseconds for the search call
     * @param hits   number of chunks returned (0 = empty result)
     */
    public void recordRetrievalSearch(String engine, long ms, int hits) {
        if ("elastic".equalsIgnoreCase(engine)) {
            elasticRetrievalCalls.incrementAndGet();
            elasticRetrievalTotalMs.addAndGet(ms);
            if (hits > 0) elasticRetrievalHits.incrementAndGet();
        } else {
            luceneRetrievalCalls.incrementAndGet();
            luceneRetrievalTotalMs.addAndGet(ms);
            if (hits > 0) luceneRetrievalHits.incrementAndGet();
        }
    }

    /** Called after each cleanup run (scheduled or manual). */
    public void recordCleanupRun(int deletedCount, long durationMs, boolean dryRun) {
        cleanupRunsTotal.incrementAndGet();
        cleanupPagesDeleted.addAndGet(deletedCount);
        cleanupLastDurationMs.set(durationMs);
        cleanupLastRunAt      = Instant.now();
        cleanupLastRunDryRun  = dryRun;
    }

    // =========================================================================
    // Snapshot — stable key-ordered map of all counters + derived metrics
    // =========================================================================

    /**
     * Returns a point-in-time snapshot of all counters and derived averages/rates.
     *
     * <p>Uses a {@link LinkedHashMap} so JSON serialisation always emits keys in the
     * same order, which makes the demo output easy to read.
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();

        // --- raw counters: throughput ---
        m.put("totalChatRequests",        totalChatRequests.get());
        m.put("totalStreamingRequests",   totalStreamingRequests.get());
        m.put("totalFallbackResponses",   totalFallbackResponses.get());
        m.put("totalNeedsSearchTriggers", totalNeedsSearchTriggers.get());

        // --- raw counters: latency ---
        m.put("totalResponseTimeMs",     totalResponseTimeMs.get());
        m.put("totalOllamaLatencyMs",    totalOllamaLatencyMs.get());
        m.put("totalEmbeddingLatencyMs", totalEmbeddingLatencyMs.get());

        // --- raw counters: sizes ---
        m.put("totalPromptChars",   totalPromptChars.get());
        m.put("totalResponseChars", totalResponseChars.get());

        // --- raw counters: BM25 ---
        m.put("bm25Searches",        bm25Searches.get());
        m.put("bm25EmptyResults",    bm25EmptyResults.get());
        m.put("bm25BoostedSearches", bm25BoostedSearches.get());

        // --- raw counters: semantic/feedback ---
        m.put("similarCasesQueries", similarCasesQueries.get());
        m.put("similarCasesHits",    similarCasesHits.get());
        m.put("embeddingFailures",   embeddingFailures.get());

        // --- raw counters: context ---
        m.put("numCtxOverflowsApprox", numCtxOverflowsApprox.get());

        // --- measured token usage (ground truth from Ollama) ---
        m.put("lastPromptTokens",    lastPromptTokens.get());
        m.put("lastEvalTokens",      lastEvalTokens.get());
        m.put("maxPromptTokens",     maxPromptTokens.get());
        m.put("numCtxOverflowsReal", numCtxOverflowsReal.get());
        long ts = tokenSamples.get();
        m.put("avgPromptTokens", ts == 0 ? 0 : sumPromptTokens.get() / ts);

        // --- raw counters: cleanup ---
        m.put("cleanupRunsTotal",      cleanupRunsTotal.get());
        m.put("cleanupPagesDeleted",   cleanupPagesDeleted.get());
        m.put("cleanupLastDurationMs", cleanupLastDurationMs.get());
        m.put("cleanupLastRunAt",      cleanupLastRunAt != null ? cleanupLastRunAt.toString() : null);
        m.put("cleanupLastRunDryRun",  cleanupLastRunDryRun);

        // --- raw counters: retrieval engine comparison ---
        m.put("luceneRetrievalCalls",    luceneRetrievalCalls.get());
        m.put("luceneRetrievalHits",     luceneRetrievalHits.get());
        m.put("luceneRetrievalTotalMs",  luceneRetrievalTotalMs.get());
        m.put("elasticRetrievalCalls",   elasticRetrievalCalls.get());
        m.put("elasticRetrievalHits",    elasticRetrievalHits.get());
        m.put("elasticRetrievalTotalMs", elasticRetrievalTotalMs.get());

        // --- derived metrics ---
        try {
            long chatReqs   = totalChatRequests.get();
            long streamReqs = totalStreamingRequests.get();
            long allReqs    = chatReqs + streamReqs;
            long simQ       = similarCasesQueries.get();
            long bm25Total  = bm25Searches.get();
            long bm25Empty  = bm25EmptyResults.get();
            long simHits    = similarCasesHits.get();

            m.put("avgResponseTimeMs",
                    avgLong(totalResponseTimeMs.get(), Math.max(chatReqs, 1)));
            m.put("avgOllamaLatencyMs",
                    avgLong(totalOllamaLatencyMs.get(), Math.max(allReqs, 1)));
            m.put("avgEmbeddingLatencyMs",
                    avgLong(totalEmbeddingLatencyMs.get(), Math.max(simQ, 1)));
            m.put("avgPromptChars",
                    avgLong(totalPromptChars.get(), Math.max(allReqs, 1)));
            m.put("avgResponseChars",
                    avgLong(totalResponseChars.get(), Math.max(allReqs, 1)));
            m.put("bm25HitRate",
                    rate(bm25Total - bm25Empty, Math.max(bm25Total, 1)));
            m.put("similarCasesHitRate",
                    rate(simHits, Math.max(simQ, 1)));
            m.put("streamingRatio",
                    rate(streamReqs, Math.max(allReqs, 1)));
        } catch (Exception ignored) {
            // snapshot must never throw
        }

        return m;
    }

    // =========================================================================
    // Reset — zero every counter atomically (best-effort, no cross-counter txn)
    // =========================================================================

    /** Zeroes all counters. Suitable for use between demo runs. */
    public void reset() {
        totalChatRequests.set(0);
        totalStreamingRequests.set(0);
        totalFallbackResponses.set(0);
        totalNeedsSearchTriggers.set(0);
        totalResponseTimeMs.set(0);
        totalOllamaLatencyMs.set(0);
        totalEmbeddingLatencyMs.set(0);
        totalPromptChars.set(0);
        totalResponseChars.set(0);
        bm25Searches.set(0);
        bm25EmptyResults.set(0);
        bm25BoostedSearches.set(0);
        similarCasesQueries.set(0);
        similarCasesHits.set(0);
        embeddingFailures.set(0);
        numCtxOverflowsApprox.set(0);
        lastPromptTokens.set(0);
        lastEvalTokens.set(0);
        maxPromptTokens.set(0);
        tokenSamples.set(0);
        sumPromptTokens.set(0);
        numCtxOverflowsReal.set(0);
        cleanupRunsTotal.set(0);
        cleanupPagesDeleted.set(0);
        cleanupLastDurationMs.set(0);
        cleanupLastRunAt     = null;
        cleanupLastRunDryRun = null;
        luceneRetrievalCalls.set(0);
        luceneRetrievalHits.set(0);
        luceneRetrievalTotalMs.set(0);
        elasticRetrievalCalls.set(0);
        elasticRetrievalHits.set(0);
        elasticRetrievalTotalMs.set(0);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Integer average (floor division). Never throws. */
    private static long avgLong(long numerator, long denominator) {
        return denominator == 0L ? 0L : numerator / denominator;
    }

    /**
     * Returns a double rate in [0.0, 1.0] formatted to 4 decimal places.
     * Uses {@link Locale#ROOT} so the decimal separator is always '.'.
     */
    private static double rate(long numerator, long denominator) {
        if (denominator == 0L) return 0.0;
        return (double) numerator / (double) denominator;
    }
}
