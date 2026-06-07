package com.kdiag.server.api;

import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.docs.index.LuceneChunkIndex;
import com.kdiag.server.maintenance.DynamicPageCleanupService;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

    private final LuceneChunkIndex luceneChunkIndex;
    private final KubernetesDocsScraper docsScraper;
    private final KubernetesDocPageRepository docPageRepository;
    private final DynamicPageCleanupService cleanupService;

    public IndexController(LuceneChunkIndex luceneChunkIndex,
                           KubernetesDocsScraper docsScraper,
                           KubernetesDocPageRepository docPageRepository,
                           DynamicPageCleanupService cleanupService) {
        this.luceneChunkIndex = luceneChunkIndex;
        this.docsScraper = docsScraper;
        this.docPageRepository = docPageRepository;
        this.cleanupService = cleanupService;
    }

    /**
     * Triggers a full async rebuild of the Lucene chunk index from Postgres.
     */
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Object>> rebuild() {
        long pageCount = docPageRepository.count();
        CompletableFuture.runAsync(() -> {
            try {
                luceneChunkIndex.rebuildAll();
            } catch (Exception e) {
                logger.error("Async index rebuild failed", e);
            }
        });
        return ResponseEntity.accepted().body(Map.of("status", "started", "pages", pageCount));
    }

    /**
     * Returns index health stats.
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Instant lastRebuild = luceneChunkIndex.getLastRebuild();
        Map<String, Object> result = new HashMap<>();
        result.put("pagesInDb", docPageRepository.count());
        result.put("chunksInIndex", luceneChunkIndex.getChunkCount());
        result.put("indexBytes", luceneChunkIndex.getIndexBytes());
        result.put("lastRebuild", lastRebuild == null ? "never" : lastRebuild.toString());
        return result;
    }

    /**
     * Runs the orphan-chunk garbage collector synchronously and returns the chunk
     * count remaining after cleanup.  Useful after truncating {@code kubernetes_doc_pages}
     * externally and then re-initialising the docs via POST /v1/index/rebuild.
     */
    @PostMapping("/gc")
    public Map<String, Object> gc() {
        int chunksAfter = luceneChunkIndex.forceGarbageCollect();
        return Map.of("chunksAfter", chunksAfter);
    }

    /**
     * Manually triggers the dynamic-page cleanup. Accepts an optional {@code dryRun}
     * query parameter (default {@code false}) to preview candidates without deleting.
     */
    @PostMapping("/cleanup-dynamic")
    public ResponseEntity<Map<String, Object>> cleanupDynamic(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        DynamicPageCleanupService.CleanupResult r = cleanupService.runCleanup(dryRun);
        return ResponseEntity.ok(Map.of(
                "candidates", r.candidates(),
                "deleted",    r.deleted(),
                "dryRun",     r.dryRun(),
                "ageDays",    r.ageDays()
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
