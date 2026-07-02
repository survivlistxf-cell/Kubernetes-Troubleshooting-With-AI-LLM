package com.kdiag.server.maintenance;

import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicPageCleanupServiceTest {

    @Mock KubernetesDocPageRepository pageRepo;
    @Mock ChunkRetriever              chunkRetriever;
    @Mock MetricsCollector            metrics;

    private DynamicPageCleanupService service;

    @BeforeEach
    void setUp() {
        service = new DynamicPageCleanupService(pageRepo, chunkRetriever, metrics);
        ReflectionTestUtils.setField(service, "enabled",       true);
        ReflectionTestUtils.setField(service, "retentionDays", 30);
        ReflectionTestUtils.setField(service, "dryRun",        false);
    }

    // -----------------------------------------------------------------------
    // Test 1: dry-run — candidates found but nothing deleted
    // -----------------------------------------------------------------------

    @Test
    void dryRun_candidatesFoundButNotDeleted() {
        List<Object[]> fakeCandidates = List.of(
                new Object[]{1L, "https://kubernetes.io/docs/a"},
                new Object[]{2L, "https://kubernetes.io/docs/b"},
                new Object[]{3L, "https://kubernetes.io/docs/c"}
        );
        when(pageRepo.findStaleDynamicPages(any())).thenReturn(fakeCandidates);

        DynamicPageCleanupService.CleanupResult result = service.runCleanup(true);

        verify(pageRepo).findStaleDynamicPages(any());
        verify(pageRepo, never()).deleteByIds(any());
        verify(chunkRetriever, never()).forceGarbageCollect();
        assertEquals(3, result.candidates());
        assertEquals(0, result.deleted());
        assertTrue(result.dryRun());
        verify(metrics).recordCleanupRun(eq(0), anyLong(), eq(true));
    }

    // -----------------------------------------------------------------------
    // Test 2: real run with no candidates — delete and GC never called
    // -----------------------------------------------------------------------

    @Test
    void realRun_noCandidates_noDeleteNoGc() {
        when(pageRepo.findStaleDynamicPages(any())).thenReturn(List.of());

        DynamicPageCleanupService.CleanupResult result = service.runCleanup(false);

        verify(pageRepo, never()).deleteByIds(any());
        verify(chunkRetriever, never()).forceGarbageCollect();
        verify(metrics).recordCleanupRun(eq(0), anyLong(), eq(false));
        assertEquals(0, result.candidates());
        assertEquals(0, result.deleted());
        assertFalse(result.dryRun());
    }

    // -----------------------------------------------------------------------
    // Test 3: real run with candidates — deletes rows and runs index GC
    // -----------------------------------------------------------------------

    @Test
    void realRun_withCandidates_deletesAndRunsGc() {
        List<Object[]> fakeCandidates = List.of(
                new Object[]{10L, "https://kubernetes.io/docs/x"},
                new Object[]{20L, "https://kubernetes.io/docs/y"}
        );
        when(pageRepo.findStaleDynamicPages(any())).thenReturn(fakeCandidates);
        when(pageRepo.deleteByIds(any())).thenReturn(2);
        when(chunkRetriever.forceGarbageCollect()).thenReturn(50);

        DynamicPageCleanupService.CleanupResult result = service.runCleanup(false);

        verify(pageRepo).deleteByIds(List.of(10L, 20L));
        verify(chunkRetriever).forceGarbageCollect();
        verify(metrics).recordCleanupRun(eq(2), anyLong(), eq(false));
        assertEquals(2, result.candidates());
        assertEquals(2, result.deleted());
        assertFalse(result.dryRun());
    }

    // -----------------------------------------------------------------------
    // Test 4: disabled — scheduledCleanup short-circuits, no repo calls
    // -----------------------------------------------------------------------

    @Test
    void disabled_scheduledCleanupShortCircuits() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.scheduledCleanup();

        verifyNoInteractions(pageRepo, chunkRetriever, metrics);
    }

    // -----------------------------------------------------------------------
    // Test 5: retention disabled (retention-days <= 0) — cleanup is a no-op,
    //         the DB is never queried/deleted and no metrics are recorded.
    //         This is the default, air-gapped-friendly behavior.
    // -----------------------------------------------------------------------

    @Test
    void retentionDisabled_keepsEverything_noOp() {
        ReflectionTestUtils.setField(service, "retentionDays", -1);

        DynamicPageCleanupService.CleanupResult result = service.runCleanup(false);

        // Nothing is queried, nothing deleted, no GC, no metrics recorded.
        verifyNoInteractions(pageRepo, chunkRetriever, metrics);
        assertEquals(0, result.candidates());
        assertEquals(0, result.deleted());
        assertEquals(-1, result.retentionDays());
    }

    // -----------------------------------------------------------------------
    // Test 6: retention disabled via scheduled run — also a no-op even when
    //         the global 'enabled' flag is true.
    // -----------------------------------------------------------------------

    @Test
    void retentionDisabled_scheduledRun_noOp() {
        ReflectionTestUtils.setField(service, "retentionDays", 0);

        service.scheduledCleanup();

        verifyNoInteractions(pageRepo, chunkRetriever, metrics);
    }

    // -----------------------------------------------------------------------
    // Test 7: retention enabled with a positive threshold — stale dynamic
    //         pages older than the cutoff are deleted and the index GC runs.
    // -----------------------------------------------------------------------

    @Test
    void retentionEnabled_withThreshold_deletesStalePages() {
        ReflectionTestUtils.setField(service, "retentionDays", 30);
        List<Object[]> fakeCandidates = List.of(
                new Object[]{100L, "https://kubernetes.io/docs/old-a"},
                new Object[]{200L, "https://kubernetes.io/docs/old-b"}
        );
        when(pageRepo.findStaleDynamicPages(any())).thenReturn(fakeCandidates);
        when(pageRepo.deleteByIds(any())).thenReturn(2);
        when(chunkRetriever.forceGarbageCollect()).thenReturn(40);

        DynamicPageCleanupService.CleanupResult result = service.runCleanup(false);

        verify(pageRepo).findStaleDynamicPages(any());
        verify(pageRepo).deleteByIds(List.of(100L, 200L));
        verify(chunkRetriever).forceGarbageCollect();
        verify(metrics).recordCleanupRun(eq(2), anyLong(), eq(false));
        assertEquals(2, result.candidates());
        assertEquals(2, result.deleted());
        assertEquals(30, result.retentionDays());
    }
}
