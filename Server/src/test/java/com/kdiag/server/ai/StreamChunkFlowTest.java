package com.kdiag.server.ai;

import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ai.helpers.ConversationSummaryService;
import com.kdiag.server.ai.helpers.NeedsSearchLoopService;
import com.kdiag.server.ai.helpers.SolveService;
import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.ollama.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for the {@link StreamChunk} sequence emitted by
 * {@link AiEngine#solveStream}.
 *
 * <p>The original tests in this class use {@code Flux.collectList().block()}
 * (reactor-core, already on the classpath via spring-boot-starter-webflux). The
 * marker-detection regression tests added below use {@code StepVerifier}
 * (reactor-test) instead, since they assert on the exact ordered sequence of
 * emitted chunks.
 *
 * <h3>Buffer mechanics</h3>
 * {@code wrapWithDynamicSearchLoop} scans every token for the marker continuously,
 * for the whole stream (not just an initial window). Text that is provably not
 * part of a marker is flushed to the sink as soon as it arrives; only an
 * in-progress marker, or a trailing fragment short enough to be the start of one,
 * is ever held back. A short token sequence with no marker is therefore flushed
 * essentially immediately — individual token granularity is still not guaranteed
 * to be preserved verbatim, since adjacent safe fragments can be coalesced.
 *
 * <p>The {@code -Dnet.bytebuddy.experimental=true} JVM flag in the
 * maven-surefire-plugin configuration enables Mockito on JDK 17+.
 */
@ExtendWith(MockitoExtension.class)
// LENIENT: @BeforeEach stubs cover all possible code paths but not every path
// is exercised by every test (e.g. getBankedTurnsBefore is only called when
// artifacts are non-empty, but all streaming tests pass null artifacts).
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamChunkFlowTest {

    @Mock OllamaClient              ollamaClient;
    @Mock KubernetesDocsScraper     docsScraper;
    @Mock HistoryService            historyService;
    @Mock FeedbackRetrievalService  feedbackRetrievalService;
    @Mock MetricsCollector          metrics;
    @Mock NeedsSearchLoopService           needsSearchLoop;
    @Mock ConversationSummaryService       conversationSummary;
    @Mock SolveService                     solveService;

    private AiEngine aiEngine;

    @BeforeEach
    void setUp() {
        aiEngine = new AiEngine(ollamaClient, docsScraper,
                historyService, feedbackRetrievalService, metrics, needsSearchLoop, conversationSummary, solveService);

        // Common stubs required by solveStream's synchronous setup phase.
        when(ollamaClient.getNumCtx()).thenReturn(4096);
        when(ollamaClient.budgetInputChars()).thenReturn(12000); // budgets derive from this now
        when(docsScraper.getRelevantDocsByBm25Boosted(any(), anyInt(), any())).thenReturn("");
        when(docsScraper.getRelevantDocs(any())).thenReturn("");
        when(feedbackRetrievalService.getBoostedUrls()).thenReturn(Set.of());
        when(feedbackRetrievalService.findSimilarCases(any())).thenReturn(List.of());
        when(historyService.getHistory(any())).thenReturn(List.of());
        when(historyService.getConversationSummary(any())).thenReturn(null);
        when(historyService.getBankedTurns(any())).thenReturn(List.of());
        when(historyService.getBankedTurnsBefore(any(), anyLong())).thenReturn(List.of());
        when(historyService.getEvictedTurnLabels(any())).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // Test 1 — plain stream: only TOKEN chunks, no STATUS chunks
    // -------------------------------------------------------------------------

    /**
     * When the LLM response contains no {@code [NEEDS_SEARCH:]} marker, every
     * emitted {@link StreamChunk} must be of type {@link StreamChunk.Type#TOKEN}
     * and at least one token must be present.
     */
    @Test
    void plainStream_emitsOnlyTokenChunks_noStatus() {
        when(ollamaClient.chatStream(any())).thenReturn(Flux.just("Hello", " world", "!"));

        List<StreamChunk> chunks = aiEngine.solveStream("conv1", "What is a pod?", null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks, "Stream must complete and return a list");
        assertFalse(chunks.isEmpty(), "Stream must emit at least one chunk");
        assertTrue(chunks.stream().noneMatch(c -> c.type() == StreamChunk.Type.STATUS),
                "No STATUS chunks expected when there is no NEEDS_SEARCH marker");
        assertTrue(chunks.stream().allMatch(c -> c.type() == StreamChunk.Type.TOKEN),
                "Every chunk must be of type TOKEN");
    }

    // -------------------------------------------------------------------------
    // Test 2 — NEEDS_SEARCH detected: first STATUS has code "searching"
    // -------------------------------------------------------------------------

    /**
     * When the first Ollama stream emits a complete {@code [NEEDS_SEARCH: query]}
     * marker (marker opened and closed in a single token), the very first element
     * of the output flux must be a STATUS chunk with code {@code "searching"}.
     */
    @Test
    void needsSearch_firstChunkIsSearchingStatus() throws Exception {
        when(ollamaClient.chatStream(any()))
                .thenReturn(Flux.just("[NEEDS_SEARCH: nginx ingress]"))  // 1st — marker
                .thenReturn(Flux.just("Nginx answer."));                  // 2nd — response

        List<StreamChunk> chunks = aiEngine.solveStream("conv2", "nginx 403", null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "Stream must emit chunks");

        StreamChunk first = chunks.get(0);
        assertEquals(StreamChunk.Type.STATUS, first.type(),
                "First chunk must be STATUS when NEEDS_SEARCH is detected");
        assertEquals("searching", first.code(),
                "First STATUS code must be 'searching'");
        assertNotNull(first.label(), "STATUS label must not be null");
        assertFalse(first.label().isBlank(), "STATUS label must not be blank");
    }

    // -------------------------------------------------------------------------
    // Test 3 — NEEDS_SEARCH + docs found: searching → search_done → TOKEN
    // -------------------------------------------------------------------------

    /**
     * When dynamic search returns non-blank results, the output must contain
     * STATUS {@code "searching"} followed by STATUS {@code "search_done"},
     * and finally at least one TOKEN chunk from the second Ollama call.
     */
    @Test
    void needsSearch_withDocs_emitsSearchDoneBeforeTokens() throws Exception {
        when(ollamaClient.chatStream(any()))
                .thenReturn(Flux.just("[NEEDS_SEARCH: crd definition]"))
                .thenReturn(Flux.just("CRD answer."));

        List<StreamChunk> chunks = aiEngine.solveStream("conv3", "explain CRD", null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);

        // Extract STATUS chunks in order
        List<StreamChunk> statusChunks = chunks.stream()
                .filter(c -> c.type() == StreamChunk.Type.STATUS)
                .toList();

        assertTrue(statusChunks.size() >= 2,
                "Expected at least 2 STATUS chunks (searching + search_done)");
        assertEquals("searching",   statusChunks.get(0).code());
        assertEquals("search_done", statusChunks.get(1).code());

        // At least one TOKEN must follow the STATUS events
        boolean tokenAfterStatus = chunks.stream()
                .anyMatch(c -> c.type() == StreamChunk.Type.TOKEN);
        assertTrue(tokenAfterStatus, "At least one TOKEN chunk must be present");
    }

    // -------------------------------------------------------------------------
    // Test 4 — NEEDS_SEARCH + empty docs: searching → search_empty → TOKEN
    // -------------------------------------------------------------------------

    /**
     * When dynamic search returns a blank/empty result, the output must contain
     * STATUS {@code "search_empty"} (not {@code "search_done"}) after STATUS
     * {@code "searching"}, then at least one TOKEN from the second Ollama call.
     */
    @Test
    void needsSearch_emptyDocs_emitsSearchEmptyBeforeTokens() throws Exception {
        when(ollamaClient.chatStream(any()))
                .thenReturn(Flux.just("[NEEDS_SEARCH: obscure topic]"))
                .thenReturn(Flux.just("Best effort answer."));

        List<StreamChunk> chunks = aiEngine.solveStream("conv4", "obscure question", null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);

        List<StreamChunk> statusChunks = chunks.stream()
                .filter(c -> c.type() == StreamChunk.Type.STATUS)
                .toList();

        assertTrue(statusChunks.size() >= 2,
                "Expected at least 2 STATUS chunks (searching + search_empty)");
        assertEquals("searching",    statusChunks.get(0).code());
        assertEquals("search_empty", statusChunks.get(1).code());

        boolean tokenPresent = chunks.stream()
                .anyMatch(c -> c.type() == StreamChunk.Type.TOKEN);
        assertTrue(tokenPresent, "At least one TOKEN chunk must be present");
    }

    // -------------------------------------------------------------------------
    // Test 5 — marker appears AFTER >256 chars of normal content
    // -------------------------------------------------------------------------

    /**
     * Regression test for the late-marker leak: when the LLM streams a long answer
     * (well past the old 256-char detection window) and only then emits
     * {@code [NEEDS_SEARCH: ...]}, the search must still be triggered and the marker
     * text must never appear in any TOKEN chunk.
     */
    @Test
    void needsSearch_afterLongContent_stillTriggersSearch_markerNeverLeaked() {
        String longPrefix = "A".repeat(300); // well past the old 256-char buffer window

        when(ollamaClient.chatStream(any()))
                .thenReturn(Flux.just(longPrefix, "[NEEDS_SEARCH: elearning backend 404 error]"))
                .thenReturn(Flux.just("Here is the real answer."));


        StepVerifier.create(aiEngine.solveStream("conv5", "backend gives 404 sometimes", null))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.TOKEN
                        && longPrefix.equals(c.text()))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.STATUS
                        && "searching".equals(c.code()))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.STATUS
                        && "search_done".equals(c.code()))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.TOKEN
                        && "Here is the real answer.".equals(c.text()))
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Test 6 — marker split across two consecutive Ollama chunks
    // -------------------------------------------------------------------------

    /**
     * The {@code [NEEDS_SEARCH:} open token can arrive split across two separate
     * Ollama-emitted chunks. Detection must still succeed and no marker fragment must
     * leak as visible text.
     */
    @Test
    void needsSearch_splitAcrossTwoChunks_stillDetected() {
        when(ollamaClient.chatStream(any()))
                .thenReturn(Flux.just("Let me check that. [NEEDS_SEA", "RCH: nginx 403]"))
                .thenReturn(Flux.just("Nginx 403 answer."));

     
        StepVerifier.create(aiEngine.solveStream("conv6", "nginx gives 403", null))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.TOKEN
                        && "Let me check that. ".equals(c.text()))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.STATUS
                        && "searching".equals(c.code()))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.STATUS
                        && "search_done".equals(c.code()))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.TOKEN
                        && "Nginx 403 answer.".equals(c.text()))
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Test 7 — regression: normal long response without marker passes through
    // -------------------------------------------------------------------------

    /**
     * A normal response with no {@code [NEEDS_SEARCH:]} marker anywhere, well over the
     * old 256-char buffer window, must pass through unchanged with no search triggered.
     */
    @Test
    void plainLongResponse_noMarker_passesThroughUnchanged_noSearch() {
        String longAnswer = "B".repeat(500);

        when(ollamaClient.chatStream(any())).thenReturn(Flux.just(longAnswer));

        StepVerifier.create(aiEngine.solveStream("conv7", "explain something long", null))
                .expectNextMatches(c -> c.type() == StreamChunk.Type.TOKEN
                        && longAnswer.equals(c.text()))
                .verifyComplete();
    }
}
