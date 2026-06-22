package com.kdiag.server.docs.index;

import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.llm.OllamaEmbeddingClient;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ElasticChunkRetriever}.
 *
 * <p>These tests avoid a real ElasticSearch instance by using a
 * {@link TestableElasticRetriever} subclass that overrides the package-private
 * {@code searchScored} method with a stub.  This lets us exercise the
 * public-facing logic (boost re-ranking, delegation, metrics recording) without
 * any network calls.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElasticChunkRetrieverTest {

    // -------------------------------------------------------------------------
    // Testable subclass — overrides searchScored so no ES client is needed
    // -------------------------------------------------------------------------

    /**
     * Subclass in the same package so it can see the package-private
     * {@code ElasticChunkRetriever.ScoredChunk} type and override
     * {@code searchScored}.
     */
    static class TestableElasticRetriever extends ElasticChunkRetriever {

        private List<ElasticChunkRetriever.ScoredChunk> stubbedResult = List.of();
        private String lastQuery;
        private int    lastTopK;

        TestableElasticRetriever(OllamaEmbeddingClient embeddingClient,
                                 KubernetesDocPageRepository pageRepository,
                                 MetricsCollector metrics) {
            // null ES client — searchScored is overridden and never calls it
            super(null, embeddingClient, pageRepository, metrics);
        }

        void stubSearchScored(List<ElasticChunkRetriever.ScoredChunk> result) {
            this.stubbedResult = result;
        }

        @Override
        List<ElasticChunkRetriever.ScoredChunk> searchScored(String queryText, int topK) {
            this.lastQuery = queryText;
            this.lastTopK  = topK;
            return stubbedResult;
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    @Mock OllamaEmbeddingClient        embeddingClient;
    @Mock KubernetesDocPageRepository  pageRepository;
    @Mock MetricsCollector             metrics;

    private TestableElasticRetriever retriever;

    private static final DocChunk CHUNK_A = new DocChunk(1L, "https://kubernetes.io/a", "Title A", 0, "text A");
    private static final DocChunk CHUNK_B = new DocChunk(2L, "https://kubernetes.io/b", "Title B", 0, "text B");
    private static final DocChunk CHUNK_C = new DocChunk(3L, "https://kubernetes.io/c", "Title C", 0, "text C");

    @BeforeEach
    void setUp() {
        retriever = new TestableElasticRetriever(embeddingClient, pageRepository, metrics);
        ReflectionTestUtils.setField(retriever, "indexName",    "kdiag-chunks");
        ReflectionTestUtils.setField(retriever, "defaultTopK",  12);
    }

    // =========================================================================
    // Test 1 — plain search delegates to searchScored and records metrics
    // =========================================================================

    @Test
    void search_delegatesToSearchScored_andRecordsMetrics() {
        retriever.stubSearchScored(List.of(
                new ElasticChunkRetriever.ScoredChunk(CHUNK_A, 1.5f),
                new ElasticChunkRetriever.ScoredChunk(CHUNK_B, 1.0f)
        ));

        List<DocChunk> results = retriever.search("pod crashloop", 5);

        assertEquals(2, results.size());
        assertEquals(CHUNK_A, results.get(0));
        assertEquals(CHUNK_B, results.get(1));

        // searchScored should have been called with topK=5
        assertEquals("pod crashloop", retriever.lastQuery);
        assertEquals(5, retriever.lastTopK);

        // Metrics must be recorded with engine="elastic"
        verify(metrics).recordRetrievalSearch(eq("elastic"), anyLong(), eq(2));
    }

    // =========================================================================
    // Test 2 — boost-aware search re-ranks: boosted URL gets 1.5x score
    // =========================================================================

    @Test
    void boostSearch_boostedUrlMovesToTop() {
        // CHUNK_B has higher raw score; CHUNK_A is lower but boosted → should win
        retriever.stubSearchScored(List.of(
                new ElasticChunkRetriever.ScoredChunk(CHUNK_B, 2.0f),  // score 2.0, not boosted
                new ElasticChunkRetriever.ScoredChunk(CHUNK_A, 1.5f),  // score 1.5 → 2.25 with boost
                new ElasticChunkRetriever.ScoredChunk(CHUNK_C, 0.5f)
        ));

        Set<String> boosted = Set.of(CHUNK_A.url());
        List<DocChunk> results = retriever.search("pod crash", 2, boosted);

        // CHUNK_A boosted: 1.5 * 1.5 = 2.25 > CHUNK_B (2.0) → CHUNK_A first
        assertEquals(2, results.size());
        assertEquals(CHUNK_A, results.get(0));
        assertEquals(CHUNK_B, results.get(1));

        // Must retrieve topK*2=4 candidates from searchScored
        assertEquals(4, retriever.lastTopK);

        verify(metrics).recordRetrievalSearch(eq("elastic"), anyLong(), eq(2));
    }

    // =========================================================================
    // Test 3 — boost-aware search with null/empty boostedUrls delegates to plain
    // =========================================================================

    @Test
    void boostSearch_emptyBoostedUrls_delegatesToPlainSearch() {
        retriever.stubSearchScored(List.of(
                new ElasticChunkRetriever.ScoredChunk(CHUNK_A, 1.0f)
        ));

        // Empty set → should not double topK
        List<DocChunk> results = retriever.search("service not found", 3, Set.of());

        assertEquals(1, results.size());
        assertEquals(CHUNK_A, results.get(0));

        // Plain search uses topK directly (not topK*2)
        assertEquals(3, retriever.lastTopK);
    }

    // =========================================================================
    // Test 4 — graceful failure: null/blank query returns empty list without throw
    // =========================================================================

    @Test
    void search_nullOrBlankQuery_returnsEmptyListGracefully() {
        // searchScored is NOT called for null/blank queries — guarded in searchScored override
        // For null query the public search() method calls searchScored which (in real impl)
        // returns empty. Our stub returns empty by default.
        retriever.stubSearchScored(List.of());

        List<DocChunk> nullResult  = retriever.search(null,  5);
        List<DocChunk> blankResult = retriever.search("   ", 5);

        assertTrue(nullResult.isEmpty(),  "null query should yield empty list");
        assertTrue(blankResult.isEmpty(), "blank query should yield empty list");

        // Metrics recorded with 0 hits (no crash)
        verify(metrics, atLeastOnce()).recordRetrievalSearch(eq("elastic"), anyLong(), eq(0));
    }
}
