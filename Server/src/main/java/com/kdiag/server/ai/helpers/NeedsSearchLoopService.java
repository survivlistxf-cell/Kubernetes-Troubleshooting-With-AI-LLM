package com.kdiag.server.ai.helpers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.ollama.OllamaClient;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class NeedsSearchLoopService {

    private static final Logger logger = LoggerFactory.getLogger(NeedsSearchLoopService.class);
    private static final String NEEDS_SEARCH_OPEN  = "[NEEDS_SEARCH:";
    private static final String NEEDS_SEARCH_CLOSE = "]";

    private final OllamaClient ollama;
    private final KubernetesDynamicSearcher dynamicSearcher;
    private final MetricsCollector metrics;

    public record DynamicRagResult(String assistantText, List<String> dynamicSourceUrls) {}
    
    public NeedsSearchLoopService(OllamaClient ollama,
                             KubernetesDynamicSearcher dynamicSearcher,
                             MetricsCollector metrics) {
        this.ollama = ollama;
        this.dynamicSearcher = dynamicSearcher;
        this.metrics = metrics;
    }
    // -------------------------------------------------------------------------
    // Streaming NEEDS_SEARCH detection loop
    // -------------------------------------------------------------------------

    /**
     * Wraps {@code firstStream} with marker detection for {@code [NEEDS_SEARCH: query]}
     * that works anywhere in the stream, not just an initial window.
     *
     * <p>Every incoming token is appended to a small internal buffer which is scanned for
     * {@link #NEEDS_SEARCH_OPEN}. Text that is provably not part of a marker is flushed to
     * the sink immediately; only a marker in progress -- or a trailing fragment short
     * enough to be the start of one -- is ever held back. This means:
     * <ul>
     *   <li>A marker found early behaves exactly as before (low latency, search triggered
     *       before any visible text reaches the client).</li>
     *   <li>A marker found after a long partial answer still triggers the search; the
     *       already-flushed prefix stays visible and the second stream's tokens are
     *       appended after it, so the marker text itself is never shown.</li>
     *   <li>A marker split across two or more Ollama chunks is still detected, because up
     *       to {@code NEEDS_SEARCH_OPEN.length() - 1} trailing characters are held back
     *       whenever they could be the start of the marker.</li>
     * </ul>
     */
    public Flux<StreamChunk> wrapWithDynamicSearchLoop(
            String convId,
            List<Map<String, String>> originalMessages,
            Flux<String> firstStream,
            AtomicReference<List<String>> sourceUrlsRef) {

        return Flux.create(sink -> {
            AtomicBoolean replaced = new AtomicBoolean(false);
            AtomicReference<Disposable> subRef = new AtomicReference<>();
            StringBuilder buf = new StringBuilder();

            Disposable d = firstStream.subscribe(
                token -> {
                    if (replaced.get()) return;
                    buf.append(token);
                    scanBuffer(convId, originalMessages, buf, sink, replaced, subRef, sourceUrlsRef);
                },
                e -> { if (!replaced.get()) sink.error(e); },
                () -> {
                    if (replaced.get()) return;
                    flushRemainder(convId, originalMessages, buf, sink, replaced, sourceUrlsRef);
                    if (!replaced.get()) sink.complete();
                }
            );
            subRef.set(d);
            sink.onCancel(d::dispose);
            sink.onDispose(d::dispose);
        });
    }

    /**
     * Consumes as much of {@code buf} as is safe to emit, flushing confirmed-marker-free
     * text as TOKEN chunks. If a complete marker is found, disposes the first stream and
     * starts the second one; otherwise leaves any unresolved marker prefix in {@code buf}
     * for the next token (or {@link #flushRemainder} on stream completion).
     */
    private void scanBuffer(String convId, List<Map<String, String>> originalMessages,
                            StringBuilder buf, FluxSink<StreamChunk> sink,
                            AtomicBoolean replaced, AtomicReference<Disposable> subRef,
                            AtomicReference<List<String>> sourceUrlsRef) {
        int openIdx = buf.indexOf(NEEDS_SEARCH_OPEN);
        if (openIdx == -1) {
            int holdBack = openMarkerPrefixOverlap(buf);
            int safeLen = buf.length() - holdBack;
            if (safeLen > 0) {
                sink.next(StreamChunk.token(buf.substring(0, safeLen)));
                buf.delete(0, safeLen);
            }
            return;
        }

        if (openIdx > 0) {
            sink.next(StreamChunk.token(buf.substring(0, openIdx)));
            buf.delete(0, openIdx);
        }

        int closeIdx = buf.indexOf(NEEDS_SEARCH_CLOSE, NEEDS_SEARCH_OPEN.length());
        if (closeIdx == -1) {
            // Marker opened but not yet closed -- hold everything back and wait for more tokens.
            return;
        }

        String query = buf.substring(NEEDS_SEARCH_OPEN.length(), closeIdx).trim();
        replaced.set(true);
        Disposable current = subRef.get();
        if (current != null) current.dispose();
        startSecondStream(convId, originalMessages, query, sink, sourceUrlsRef);
    }

    /**
     * Resolves whatever is left in {@code buf} once the first stream completes without
     * having been replaced. A dangling, never-closed marker at the very end of the
     * response is treated as a malformed-but-intentional search request -- robustness
     * against models that don't respect the closing bracket -- using whatever text
     * follows the open marker as the query. Plain trailing text (no marker) is flushed
     * unchanged.
     */
    private void flushRemainder(String convId, List<Map<String, String>> originalMessages,
                                StringBuilder buf, FluxSink<StreamChunk> sink,
                                AtomicBoolean replaced, AtomicReference<List<String>> sourceUrlsRef) {
        int openIdx = buf.indexOf(NEEDS_SEARCH_OPEN);
        if (openIdx == -1) {
            if (buf.length() > 0) sink.next(StreamChunk.token(buf.toString()));
            return;
        }

        if (openIdx > 0) {
            sink.next(StreamChunk.token(buf.substring(0, openIdx)));
        }

        int closeIdx = buf.indexOf(NEEDS_SEARCH_CLOSE, openIdx + NEEDS_SEARCH_OPEN.length());
        String query = closeIdx != -1
                ? buf.substring(openIdx + NEEDS_SEARCH_OPEN.length(), closeIdx).trim()
                : buf.substring(openIdx + NEEDS_SEARCH_OPEN.length()).trim();
        if (query.isEmpty()) {
            return;
        }
        replaced.set(true);
        startSecondStream(convId, originalMessages, query, sink, sourceUrlsRef);
    }

    /**
     * Returns the length of the longest suffix of {@code buf} that matches a prefix of
     * {@link #NEEDS_SEARCH_OPEN} -- i.e. how many trailing characters could be the start
     * of a marker split across the next token. Returns 0 if no suffix matches.
     */
    private static int openMarkerPrefixOverlap(StringBuilder buf) {
        int maxLen = Math.min(buf.length(), NEEDS_SEARCH_OPEN.length() - 1);
        for (int len = maxLen; len > 0; len--) {
            int start = buf.length() - len;
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (buf.charAt(start + i) != NEEDS_SEARCH_OPEN.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) return len;
        }
        return 0;
    }

    /**
     * Runs {@link KubernetesDynamicSearcher#searchAndSave} on a
     * {@link Schedulers#boundedElastic()} thread (blocking call, must not run on Netty
     * event-loop), then starts a fresh Ollama streaming call with the retrieved docs
     * appended and pipes its tokens into {@code sink}.
     */
    private void startSecondStream(String convId,
                                   List<Map<String, String>> originalMessages,
                                   String searchQuery,
                                   FluxSink<StreamChunk> sink,
                                   AtomicReference<List<String>> sourceUrlsRef) {
        logger.info("[stream] NEEDS_SEARCH triggered, query='{}'", searchQuery);
        metrics.recordNeedsSearchTrigger();

        // Notify the frontend that a dynamic search is in progress.
        sink.next(StreamChunk.status("searching", "Se caută documentație suplimentară..."));

        final int dynCap = BudgetComputing.dynamicDocCapFor(ollama.budgetInputChars());
        Mono.fromCallable(() -> dynamicSearcher.searchAndSave(convId, searchQuery, dynCap))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(searchResult -> {
                    String dynamicDocs = searchResult.context();
                    if (searchResult.urls() != null && !searchResult.urls().isEmpty()) {
                        sourceUrlsRef.set(searchResult.urls());
                    }
                    List<Map<String, String>> augmented = new java.util.ArrayList<>(originalMessages);
                    if (dynamicDocs != null && !dynamicDocs.isBlank()) {
                        sink.next(StreamChunk.status("search_done",
                                "Documentație găsită. Se generează răspunsul..."));
                        // Clip docs to the leftover window (RAG share) so the second stream stays in num_ctx.
                        augmented.add(Map.of("role", "user", "content",
                                BudgetComputing.dynamicDocsMessage(dynamicDocs, BudgetComputing.dynamicDocBudget(originalMessages, ollama.budgetInputChars()))));
                    } else {
                        logger.info("[stream] Dynamic search returned nothing; asking LLM to continue without it.");
                        sink.next(StreamChunk.status("search_empty",
                                "Căutarea nu a returnat rezultate. Se generează răspunsul..."));
                        augmented.add(Map.of("role", "user",
                                "content",
                                "Search yielded no new results. Please provide your best diagnosis or advice."));
                    }
                    return ollama.chatStream(augmented);
                })
                .subscribe(token -> sink.next(StreamChunk.token(token)), sink::error, sink::complete);
    }

    //-----------------------------------------------------
    //Function used in non-streaming solve() function from AiEngine.java
    //------------------------------------------------------
    public DynamicRagResult dynamicRagLoopFunction(String conversationId, String assistantText, 
                                        List<Map<String, String>> messages){
        List<String> dynamicSourceUrls = null;
        if (assistantText.contains("[NEEDS_SEARCH:")) {
            metrics.recordNeedsSearchTrigger();
            int startIdx = assistantText.indexOf("[NEEDS_SEARCH:") + 14;
            int endIdx = assistantText.indexOf("]", startIdx);
            if (endIdx != -1) {
                String query = assistantText.substring(startIdx, endIdx).trim();
                logger.info("LLM requested dynamic search for: {}", query);

                KubernetesDynamicSearcher.SearchResult searchResult =
                        dynamicSearcher.searchAndSave(conversationId, query, BudgetComputing.dynamicDocCapFor(ollama.budgetInputChars()));
                String dynamicDocsText = searchResult.context();
                if (searchResult.urls() != null && !searchResult.urls().isEmpty()) {
                    dynamicSourceUrls = searchResult.urls();
                }

                if (!dynamicDocsText.isBlank()) {
                    // Add the marker turn first, then budget the docs against what's left of the
                    // window so the second call cannot overflow num_ctx (clipped to the RAG share).
                    messages.add(Map.of("role", "assistant", "content", assistantText));
                    String docMsg = BudgetComputing.dynamicDocsMessage(dynamicDocsText, BudgetComputing.dynamicDocBudget(messages, ollama.budgetInputChars()));
                    messages.add(Map.of("role", "user", "content", docMsg));
                    try {
                        logger.info("Sending secondary request to Ollama with dynamic docs context...");
                        assistantText = ollama.chat(messages).trim();
                    } catch (Exception e) {
                        logger.error("Ollama secondary call failed", e);
                    }
                } else {
                    logger.info("Dynamic search found nothing. Asking LLM to continue without it.");
                    messages.add(Map.of("role", "assistant", "content", assistantText));
                    messages.add(Map.of("role", "user", "content",
                            "Search yielded no new results. Please provide your best diagnosis or advice."));
                    try {
                        assistantText = ollama.chat(messages).trim();
                    } catch (Exception e) {
                    }
                }
            }
        }
        return new DynamicRagResult(assistantText, dynamicSourceUrls);
    }
}
