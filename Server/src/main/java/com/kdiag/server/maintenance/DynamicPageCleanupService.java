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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled service that removes stale dynamic Kubernetes documentation pages.
 *
 * <p>A page is eligible for deletion when:
 * <ul>
 *   <li>it was discovered via [NEEDS_SEARCH:] ({@code is_dynamic = true}), and</li>
 *   <li>its {@code last_scraped} timestamp is older than {@code kdiag.docs.dynamic.retention-days}, and</li>
 *   <li>its URL does not appear in any positively-rated {@code problem_resolutions} row.</li>
 * </ul>
 *
 * <p>After deleting rows from {@code kubernetes_doc_pages} the active retriever's
 * garbage collector is run so the index stays consistent with the database.
 *
 * <p><b>Retention default:</b> {@code kdiag.docs.dynamic.retention-days} defaults to
 * {@code -1}, meaning "keep all accumulated dynamic docs forever". When the value is
 * {@code <= 0} the job is a no-op (it logs once that it is disabled), so the system
 * stays capable when running air-gapped. A positive value re-enables pruning of pages
 * older than that many days.
 */
@Service
public class DynamicPageCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(DynamicPageCleanupService.class);

    @Value("${kdiag.cleanup.dynamic.enabled:true}")
    private boolean enabled;

    /**
     * Retention window in days. {@code <= 0} (default {@code -1}) disables cleanup and
     * keeps every dynamically-scraped page forever (air-gapped friendly).
     */
    @Value("${kdiag.docs.dynamic.retention-days:-1}")
    private int retentionDays;

    @Value("${kdiag.cleanup.dynamic.dry-run:false}")
    private boolean dryRun;

    /** Ensures the "cleanup disabled" message is logged only once, not on every run. */
    private final AtomicBoolean disabledLogged = new AtomicBoolean(false);

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

        // Retention <= 0 means "keep forever": never touch the database, never run GC.
        if (retentionDays <= 0) {
            if (disabledLogged.compareAndSet(false, true)) {
                logger.info("Dynamic cleanup disabled (kdiag.docs.dynamic.retention-days={} <= 0); "
                          + "keeping all dynamic docs indefinitely (air-gapped friendly)", retentionDays);
            }
            return new CleanupResult(0, 0, dryRunOverride, retentionDays);
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Object[]> candidates = pageRepo.findStaleDynamicPages(cutoff);

        if (candidates.isEmpty()) {
            logger.info("Dynamic cleanup: 0 stale pages found (cutoff={}, retentionDays={})",
                        cutoff, retentionDays);
            metrics.recordCleanupRun(0, System.currentTimeMillis() - start, dryRunOverride);
            return new CleanupResult(0, 0, dryRunOverride, retentionDays);
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
        return new CleanupResult(candidates.size(), deleted, dryRunOverride, retentionDays);
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record CleanupResult(int candidates, int deleted, boolean dryRun, int retentionDays) {}
}
