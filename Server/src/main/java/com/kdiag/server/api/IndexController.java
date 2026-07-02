package com.kdiag.server.api;

import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.docs.index.ElasticChunkRetriever;
import com.kdiag.server.docs.index.LuceneChunkIndex;
import com.kdiag.server.maintenance.DynamicPageCleanupService;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/v1/index")
public class IndexController {

    private static final Logger logger = LoggerFactory.getLogger(IndexController.class);

    /** Active retrieval engine (Lucene or ES, depending on kdiag.retrieval.engine). */
    private final ChunkRetriever activeRetriever;

    /**
     * Lucene index — always present regardless of active engine.
     * Kept so the Lucene-specific rebuild/stats endpoint remains available even
     * when ES is the active engine (useful for A/B comparison experiments).
     */
    private final LuceneChunkIndex luceneChunkIndex;

    /**
     * ElasticSearch retriever — {@code null} when {@code kdiag.retrieval.engine=lucene}.
     * Injected optionally so the controller compiles and starts in Lucene-only mode.
     */
    @Nullable
    private final ElasticChunkRetriever elasticChunkRetriever;

    private final KubernetesDocsScraper docsScraper;
    private final KubernetesDocPageRepository docPageRepository;
    private final DynamicPageCleanupService cleanupService;

    public IndexController(
            @Qualifier("activeChunkRetriever") ChunkRetriever activeRetriever,
            LuceneChunkIndex luceneChunkIndex,
            @Nullable ElasticChunkRetriever elasticChunkRetriever,
            KubernetesDocsScraper docsScraper,
            KubernetesDocPageRepository docPageRepository,
            DynamicPageCleanupService cleanupService) {
        this.activeRetriever       = activeRetriever;
        this.luceneChunkIndex      = luceneChunkIndex;
        this.elasticChunkRetriever = elasticChunkRetriever;
        this.docsScraper           = docsScraper;
        this.docPageRepository     = docPageRepository;
        this.cleanupService        = cleanupService;
    }

    // -------------------------------------------------------------------------
    // Active-engine endpoints
    // -------------------------------------------------------------------------

    /**
     * Triggers a full async rebuild of the <em>active</em> chunk index from Postgres.
     * In Lucene mode this rebuilds the BM25 index; in ES mode it re-indexes into ES.
     */
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Object>> rebuild() {
        long pageCount = docPageRepository.count();
        CompletableFuture.runAsync(() -> {
            try {
                activeRetriever.rebuildAll();
            } catch (Exception e) {
                logger.error("Async index rebuild failed", e);
            }
        });
        return ResponseEntity.accepted().body(Map.of("status", "started", "pages", pageCount));
    }

    /**
     * Returns index-health stats for the active retrieval engine.
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Instant lastRebuild = activeRetriever.getLastRebuild();
        Map<String, Object> result = new HashMap<>();
        result.put("pagesInDb",     docPageRepository.count());
        result.put("chunksInIndex", activeRetriever.getChunkCount());
        result.put("indexBytes",    activeRetriever.getIndexBytes());
        result.put("lastRebuild",   lastRebuild == null ? "never" : lastRebuild.toString());
        result.put("engine",        activeRetriever.getClass().getSimpleName());
        return result;
    }

    /**
     * Runs the orphan-chunk garbage collector on the <em>active</em> engine synchronously
     * and returns the chunk count remaining after cleanup.
     */
    @PostMapping("/gc")
    public Map<String, Object> gc() {
        int chunksAfter = activeRetriever.forceGarbageCollect();
        return Map.of("chunksAfter", chunksAfter);
    }

    // -------------------------------------------------------------------------
    // ElasticSearch-specific endpoints
    // -------------------------------------------------------------------------

    /**
     * Re-indexes all existing {@code kubernetes_doc_pages} rows from Postgres into
     * ElasticSearch without re-scraping any URLs.  Useful for the initial backfill
     * when switching from Lucene to ES mode.
     *
     * <p>Runs asynchronously; returns {@code 202 Accepted} immediately.
     * Returns {@code 503} if ElasticSearch is not configured.
     */
    @PostMapping("/es-backfill")
    public ResponseEntity<Map<String, Object>> esBackfill() {
        if (elasticChunkRetriever == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "ElasticSearch retriever is not active. " +
                             "Set kdiag.retrieval.engine=elastic to enable it."));
        }
        long pageCount = docPageRepository.count();
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("ES backfill started: {} pages to re-index", pageCount);
                elasticChunkRetriever.rebuildAll();
                logger.info("ES backfill complete");
            } catch (Exception e) {
                logger.error("ES backfill failed", e);
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "pages",  pageCount,
                "note",   "Re-indexing existing pages into ElasticSearch without re-scraping"
        ));
    }

    // -------------------------------------------------------------------------
    // Lucene-specific endpoints (always available for A/B comparison)
    // -------------------------------------------------------------------------

    /**
     * Triggers an async full rebuild of the <em>Lucene</em> index specifically,
     * even when ES is the active engine.  Useful for A/B comparison experiments.
     */
    @PostMapping("/lucene-rebuild")
    public ResponseEntity<Map<String, Object>> luceneRebuild() {
        long pageCount = docPageRepository.count();
        CompletableFuture.runAsync(() -> {
            try {
                luceneChunkIndex.rebuildAll();
            } catch (Exception e) {
                logger.error("Async Lucene rebuild failed", e);
            }
        });
        return ResponseEntity.accepted().body(Map.of("status", "started", "pages", pageCount,
                "engine", "lucene"));
    }

    /**
     * Returns stats for the Lucene index specifically (useful in ES mode for comparison).
     */
    @GetMapping("/lucene-stats")
    public Map<String, Object> luceneStats() {
        Instant lastRebuild = luceneChunkIndex.getLastRebuild();
        Map<String, Object> result = new HashMap<>();
        result.put("chunksInIndex", luceneChunkIndex.getChunkCount());
        result.put("indexBytes",    luceneChunkIndex.getIndexBytes());
        result.put("lastRebuild",   lastRebuild == null ? "never" : lastRebuild.toString());
        result.put("engine",        "lucene");
        return result;
    }

    // -------------------------------------------------------------------------
    // Maintenance endpoints
    // -------------------------------------------------------------------------

    /**
     * Manually triggers the dynamic-page cleanup. Accepts an optional {@code dryRun}
     * query parameter (default {@code false}) to preview candidates without deleting.
     */
    @PostMapping("/cleanup-dynamic")
    public ResponseEntity<Map<String, Object>> cleanupDynamic(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        DynamicPageCleanupService.CleanupResult r = cleanupService.runCleanup(dryRun);
        return ResponseEntity.ok(Map.of(
                "candidates",    r.candidates(),
                "deleted",       r.deleted(),
                "dryRun",        r.dryRun(),
                "retentionDays", r.retentionDays()
        ));
    }

    /**
     * Re-fetches pages whose text was stored at exactly 20 000 chars (the old truncation ceiling).
     * Rate-limited to 1 request/second. Runs asynchronously; returns 202 immediately.
     */
    @PostMapping("/refresh-stale")
    public ResponseEntity<Map<String, Object>> refreshStale() {
        long suspectedStale = docPageRepository.findAll().stream()
                .filter(p -> p.getTextContent() != null && p.getTextContent().length() == 20000)
                .count();

        CompletableFuture.runAsync(() -> {
            try {
                int refreshed = docsScraper.refreshStalePages();
                logger.info("Stale-page refresh finished: {} pages updated", refreshed);
            } catch (Exception e) {
                logger.error("Async stale-page refresh failed", e);
            }
        });

        return ResponseEntity.accepted()
                .body(Map.of("status", "started", "suspectedStalePages", suspectedStale));
    }
}
