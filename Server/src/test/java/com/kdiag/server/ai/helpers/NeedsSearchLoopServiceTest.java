package com.kdiag.server.ai.helpers;

import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.docs.KubernetesDynamicSearcher.SearchResult;
import com.kdiag.server.llm.GptChatClient;
import com.kdiag.server.metrics.MetricsCollector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@link NeedsSearchLoopService} second-pass guards.
 *
 * <p>Background: the first gpt-oss stream emits {@code [NEEDS_SEARCH: ...]}, which is correctly
 * intercepted and triggers a dynamic search. The bug was that the <em>second</em> (post-search)
 * stream was piped to the client raw — so when the model emitted another marker on the second
 * pass, that raw marker leaked to the UI and was persisted as the assistant answer.
 *
 * <p>These tests exercise {@link NeedsSearchLoopService#wrapWithDynamicSearchLoop} directly (not
 * through {@code AiEngine}) so the real detection/stripping code runs, and assert that:
 * <ul>
 *   <li>a marker echoed by the second stream is stripped and never appears in any TOKEN chunk;</li>
 *   <li>the surrounding real answer survives;</li>
 *   <li>exactly one search is triggered (the second marker must NOT start a third pass).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeedsSearchLoopServiceTest {

    @Mock GptChatClient            gpt;
    @Mock KubernetesDynamicSearcher dynamicSearcher;
    @Mock MetricsCollector         metrics;

    private NeedsSearchLoopService service;

    private static final List<Map<String, String>> ORIGINAL_MESSAGES = List.of(
            Map.of("role", "system", "content", "You are a Kubernetes diagnostics assistant."),
            Map.of("role", "user",   "content", "Search for ingress controller problems"));

    @BeforeEach
    void setUp() {
        service = new NeedsSearchLoopService(gpt, dynamicSearcher, metrics);
        when(gpt.budgetInputChars()).thenReturn(12000);
    }

    private String visibleText(List<StreamChunk> chunks) {
        return chunks.stream()
                .filter(c -> c.type() == StreamChunk.Type.TOKEN)
                .map(StreamChunk::text)
                .collect(Collectors.joining());
    }

    // -------------------------------------------------------------------------
    // Core regression: second stream echoes a marker -> must be stripped, no re-search
    // -------------------------------------------------------------------------

    @Test
    void secondStreamMarker_isStripped_notLeaked_andNoSecondSearch() {
        // First stream requests a search (correctly intercepted, never shown).
        Flux<String> firstStream = Flux.just(
                "[NEEDS_SEARCH: ingress controller troubleshooting common problems]");

        // Search returns usable docs.
        when(dynamicSearcher.searchAndSave(any(), any(), anyInt()))
                .thenReturn(new SearchResult("Official ingress docs: an Ingress exposes HTTP routes.",
                        List.of("https://kubernetes.io/docs/concepts/services-networking/ingress/")));

        // Second (post-search) stream misbehaves: it emits real text AND another marker.
        when(gpt.chatStream(any())).thenReturn(Flux.just(
                "Here is the diagnosis. ",
                "[NEEDS_SEARCH: ingress controller problems]",
                "Check your IngressClass."));

        AtomicReference<List<String>> urls = new AtomicReference<>();
        List<StreamChunk> chunks = service
                .wrapWithDynamicSearchLoop("conv1", ORIGINAL_MESSAGES, firstStream, urls)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        String visible = visibleText(chunks);

        // The marker must never reach the client, from either pass.
        assertFalse(visible.contains("[NEEDS_SEARCH"),
                "No NEEDS_SEARCH marker may appear in visible tokens, got: " + visible);
        // The real answer around the stripped marker survives.
        assertTrue(visible.contains("Here is the diagnosis."), "First fragment must survive");
        assertTrue(visible.contains("Check your IngressClass."), "Last fragment must survive");

        // Exactly ONE search: the second-pass marker must not trigger another search.
        verify(dynamicSearcher, times(1)).searchAndSave(any(), any(), anyInt());

        // Source URLs from the (single) search are surfaced.
        assertNotNull(urls.get());
        assertEquals(1, urls.get().size());
    }

    // -------------------------------------------------------------------------
    // Second-pass marker split across chunks is still stripped
    // -------------------------------------------------------------------------

    @Test
    void secondStreamMarker_splitAcrossChunks_isStripped() {
        Flux<String> firstStream = Flux.just("[NEEDS_SEARCH: nginx 403]");

        when(dynamicSearcher.searchAndSave(any(), any(), anyInt()))
                .thenReturn(new SearchResult("Some docs.", List.of("https://kubernetes.io/docs/x/")));

        // Marker open token split across two emitted chunks.
        when(gpt.chatStream(any())).thenReturn(Flux.just(
                "Answer start. ", "[NEEDS_SE", "ARCH: nginx ingress]", " Answer end."));

        List<StreamChunk> chunks = service
                .wrapWithDynamicSearchLoop("conv2", ORIGINAL_MESSAGES, firstStream, new AtomicReference<>())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        String visible = visibleText(chunks);
        assertFalse(visible.contains("NEEDS_SE"),
                "No marker fragment may leak, got: " + visible);
        assertTrue(visible.contains("Answer start."));
        assertTrue(visible.contains("Answer end."));
    }

    // -------------------------------------------------------------------------
    // Happy path unchanged: clean second stream passes through verbatim
    // -------------------------------------------------------------------------

    @Test
    void cleanSecondStream_passesThroughUnchanged() {
        Flux<String> firstStream = Flux.just("[NEEDS_SEARCH: pod crashloop]");

        when(dynamicSearcher.searchAndSave(any(), any(), anyInt()))
                .thenReturn(new SearchResult("Docs about CrashLoopBackOff.",
                        List.of("https://kubernetes.io/docs/y/")));
        when(gpt.chatStream(any())).thenReturn(Flux.just("A pod in CrashLoopBackOff keeps restarting."));

        List<StreamChunk> chunks = service
                .wrapWithDynamicSearchLoop("conv3", ORIGINAL_MESSAGES, firstStream, new AtomicReference<>())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        assertEquals("A pod in CrashLoopBackOff keeps restarting.", visibleText(chunks));
        // STATUS sequence is still emitted for the UI.
        List<StreamChunk> status = chunks.stream()
                .filter(c -> c.type() == StreamChunk.Type.STATUS).toList();
        assertTrue(status.size() >= 2);
        assertEquals("searching",   status.get(0).code());
        assertEquals("search_done", status.get(1).code());
    }

    // -------------------------------------------------------------------------
    // Unit coverage for the static string stripper
    // -------------------------------------------------------------------------

    @Test
    void stripSearchMarkers_handlesClosedDanglingAndNone() {
        assertEquals("before  after",
                NeedsSearchLoopService.stripSearchMarkers("before [NEEDS_SEARCH: x] after"));
        assertEquals("only this",
                NeedsSearchLoopService.stripSearchMarkers("only this [NEEDS_SEARCH: dangling no close"));
        assertEquals("nothing to strip",
                NeedsSearchLoopService.stripSearchMarkers("nothing to strip"));
        assertEquals("", NeedsSearchLoopService.stripSearchMarkers("[NEEDS_SEARCH: whole thing]"));
        assertNull(NeedsSearchLoopService.stripSearchMarkers(null));
    }
}
