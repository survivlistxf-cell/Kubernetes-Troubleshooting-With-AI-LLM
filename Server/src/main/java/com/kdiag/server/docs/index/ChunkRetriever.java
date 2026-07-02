package com.kdiag.server.docs.index;

import com.kdiag.server.entities.KubernetesDocPage;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Common retrieval contract that abstracts over concrete index backends
 * (currently: Lucene BM25, ElasticSearch hybrid BM25+kNN).
 *
 * <p>AiEngine and the surrounding scraper/searcher components program to this
 * interface; the concrete implementation is selected at startup by
 * {@code kdiag.retrieval.engine=lucene|elastic}.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Write operations ({@link #indexPage}, {@link #rebuildAll}) must be
 *       idempotent: indexing the same page twice replaces the earlier version.</li>
 *   <li>Read operations must never throw — implementation must catch all internal
 *       errors and return an empty list / 0 on failure.</li>
 * </ul>
 */
public interface ChunkRetriever {

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Splits {@code page}'s text into chunks and adds them to the index.
     * Existing chunks for the same page ID are replaced atomically.
     */
    void indexPage(KubernetesDocPage page);

    /**
     * Drops the entire index and rebuilds it from every row currently in the
     * {@code kubernetes_doc_pages} table.
     */
    void rebuildAll();

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code topK} best-matching chunks for {@code queryText}.
     * Never throws; returns an empty list on any error.
     */
    List<DocChunk> search(String queryText, int topK);

    /**
     * Boost-aware variant: retrieves {@code topK * 2} candidates, multiplies
     * each chunk's score by {@code 1.5} when its URL is in {@code boostedUrls},
     * re-sorts by adjusted score, and trims to {@code topK}.
     *
     * <p>Delegates to {@link #search(String, int)} when {@code boostedUrls}
     * is {@code null} or empty.
     */
    List<DocChunk> search(String queryText, int topK, Set<String> boostedUrls);

    // -------------------------------------------------------------------------
    // Relevance gate (optional per-implementation)
    // -------------------------------------------------------------------------

    /**
     * Minimum semantic relevance required for retrieval results to count as usable
     * context. When the best match falls below this threshold, {@link #search} returns
     * an empty list — the caller then presents an empty documentation block to the LLM,
     * which (per the system-prompt contract) makes it emit a {@code [NEEDS_SEARCH:]}
     * marker and fall back to live documentation search.
     *
     * <p>Default implementations are no-ops so that backends without a comparable
     * absolute score (e.g. Lucene BM25, whose scores are query-dependent and unbounded)
     * simply ignore the gate.
     */
    default void setMinRelevance(double minRelevance) { /* no-op by default */ }

    /** Current relevance gate; 0.0 means disabled. */
    default double getMinRelevance() { return 0.0; }

    // -------------------------------------------------------------------------
    // Maintenance operations
    // -------------------------------------------------------------------------

    /**
     * Removes index entries whose page no longer exists in the database.
     *
     * @return the chunk count remaining after cleanup
     */
    int forceGarbageCollect();

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    /** Total number of indexed chunks (documents). */
    int getChunkCount();

    /** Approximate size of the index on disk in bytes. */
    long getIndexBytes();

    /** Timestamp of the last completed {@link #rebuildAll} call, or {@code null}. */
    Instant getLastRebuild();
}
