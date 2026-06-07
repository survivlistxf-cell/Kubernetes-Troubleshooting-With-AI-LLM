package com.kdiag.server.maintenance;

import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled service that removes stale dynamic Kubernetes documentation pages.
 *
 * <p>A page is eligible for deletion when:
 * <ul>
 *   <li>it was discovered via [NEEDS_SEARCH:] ({@code is_dynamic = true}), and</li>
 *   <li>its {@code last_scraped} timestamp is older than {@code kdiag.cleanup.dynamic.age-days}, and</li>
 *   <li>its URL does not appear in any positively-rated {@code problem_resolutions} row.</li>
 * </ul>
 *
 * <p>After deleting rows from {@code kubernetes_doc_pages} the active retriever's
 * garbage collector is run so the index stays consistent with the database.
 */
@Service
public class DynamicPageCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(DynamicPageCleanupService.class);

    @Value("${kdiag.cleanup.dynamic.enabled:true}")
    private boolean enabled;

    @Value("${kdiag.cleanup.dynamic.age-days:30}")
    private int ageDays;

    @Value("${kdiag.cleanup.dynamic.dry-run:false}")
    private boolean dryRun;

    private final KubernetesDocPageRepository pageRepo;
    private final ChunkRetriever chunkRetriever;
    private final MetricsCollector metrics;

    public DynamicPageCleanupService(KubernetesDocPageRepository pageRepo,
                                     @Qualifier("activeChunkRetriever") ChunkRetriever chunkRetriever,
                                     MetricsCollector metrics) {
        this.pageRepo       = pageRepo;
        this.chunkRetriever = chunkRetriever;
        this.metrics        = metrics;
    }

    // -------------------------------------------------------------------------
    // Scheduled entry point
    // -------------------------------------------------------------------------

    @Scheduled(cron = "${kdiag.cleanup.dynamic.cron:0 0 3 * * SUN}")
    public void scheduledCleanup() {
        if (!enabled) {
            logger.debug("Dynamic cleanup is disabled; skipping scheduled run");
            return;
        }
        runCleanup(dryRun);
    }

    // -------------------------------------------------------------------------
    // Core cleanup logic — public so the manual endpoint and tests can call it
    // -------------------------------------------------------------------------

    /**
     * Queries for stale dynamic pages, optionally deletes them, runs the Lucene GC,
     * and records metrics.
     *
     * @param dryRunOverride when {@code true} the query runs but no rows are deleted
     * @return a {@link CleanupResult} describing what happened
     */
    public CleanupResult runCleanup(boolean dryRunOverride) {
        long start = System.currentTimeMillis();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(ageDays);
        List<Object[]> candidates = pageRepo.findStaleDynamicPages(cutoff);

        if (candidates.isEmpty()) {
            logger.info("Dynamic cleanup: 0 stale pages found (cutoff={}, ageDays={})",
                        cutoff, ageDays);
            metrics.recordCleanupRun(0, System.currentTimeMillis() - start, dryRunOverride);
            return new CleanupResult(0, 0, dryRunOverride, ageDays);
        }

        List<Long> ids = new ArrayList<>(candidates.size());
        for (Object[] row : candidates) {
            ids.add(((Number) row[0]).longValue());
            logger.info("Cleanup candidate: id={} url={}", row[0], row[1]);
        }

        int deleted = 0;
        if (!dryRunOverride) {
            deleted = pageRepo.deleteByIds(ids);
            chunkRetriever.forceGarbageCollect();
            logger.info("Dynamic cleanup: deleted {} pages, ran index GC", deleted);
        } else {
            logger.info("Dynamic cleanup DRY-RUN: would delete {} pages", ids.size());
        }

        long elapsed = System.currentTimeMillis() - start;
        metrics.recordCleanupRun(deleted, elapsed, dryRunOverride);
        return new CleanupResult(candidates.size(), deleted, dryRunOverride, ageDays);
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record CleanupResult(int candidates, int deleted, boolean dryRun, int ageDays) {}
}
