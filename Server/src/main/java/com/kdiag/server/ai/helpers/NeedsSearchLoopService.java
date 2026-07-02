package com.kdiag.server.ai.helpers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.config.AblationConfig;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.llm.GptChatClient;

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

    private final GptChatClient gpt;
    private final KubernetesDynamicSearcher dynamicSearcher;
    private final MetricsCollector metrics;
    private final AblationConfig ablation;

    public record DynamicRagResult(String assistantText, List<String> dynamicSourceUrls) {}

    public NeedsSearchLoopService(GptChatClient gpt,
                             KubernetesDynamicSearcher dynamicSearcher,
                             MetricsCollector metrics,
                             AblationConfig ablation) {
        this.gpt = gpt;
        this.dynamicSearcher = dynamicSearcher;
        this.metrics = metrics;
        this.ablation = ablation;
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
     *   <li>A marker split across two or more streamed chunks is still detected, because up
     *       to {@code NEEDS_SEARCH_OPEN.length() - 1} trailing characters are held back
     *       whenever they could be the start of the marker.</li>
     * </ul>
     */
    public Flux<StreamChunk> wrapWithDynamicSearchLoop(
            String convId,
            List<Map<String, String>> originalMessages,
            Flux<String> firstStream,
            AtomicReference<List<String>> sourceUrlsRef) {

        // Ablation switch: with dynamic search disabled (config "none"/"static") never trigger
        // a live search — just strip any [NEEDS_SEARCH:] marker a non-compliant model may still
        // emit, so the marker never reaches the client or the persisted history.
        if (!ablation.isDynamicSearchEnabled()) {
            return stripMarkerStream(firstStream).map(StreamChunk::token);
        }

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

    // -------------------------------------------------------------------------
    // Second-pass guards: after the first search, the model must answer, not search again
    // -------------------------------------------------------------------------

    /**
     * Returns a copy of {@code messages} with {@link PromptsBuilder#SECOND_PASS_NO_SEARCH} appended to
     * the system prompt (or prepended as a fresh system message if none exists). The original list is
     * left untouched. Used for the post-search call so the model answers from the supplied docs rather
     * than emitting another {@code [NEEDS_SEARCH:]} marker.
     */
    private static List<Map<String, String>> withSecondPassSystemDirective(List<Map<String, String>> messages) {
        List<Map<String, String>> out = new java.util.ArrayList<>(messages.size() + 1);
        boolean patched = false;
        for (Map<String, String> m : messages) {
            if (!patched && "system".equals(m.get("role"))) {
                out.add(Map.of("role", "system",
                        "content", m.getOrDefault("content", "") + PromptsBuilder.SECOND_PASS_NO_SEARCH));
                patched = true;
            } else {
                out.add(m);
            }
        }
        if (!patched) {
            out.add(0, Map.of("role", "system", "content", PromptsBuilder.SECOND_PASS_NO_SEARCH.trim()));
        }
        return out;
    }

    /**
     * Streaming marker stripper: forwards tokens unchanged except for any {@code [NEEDS_SEARCH: ...]}
     * marker, which is removed (even when split across chunks). Unlike {@link #wrapWithDynamicSearchLoop}
     * this never triggers a search; it is a pure safety net for the second pass.
     */
    private static Flux<String> stripMarkerStream(Flux<String> in) {
        StringBuilder buf = new StringBuilder();
        return Flux.concat(
                in.<String>handle((token, sink) -> {
                    buf.append(token);
                    String out = drainStrippable(buf);
                    if (!out.isEmpty()) sink.next(out);
                }),
                Flux.defer(() -> {
                    String rest = stripSearchMarkers(buf.toString());
                    buf.setLength(0);
                    return (rest == null || rest.isEmpty()) ? Flux.<String>empty() : Flux.just(rest);
                }));
    }

    /**
     * Pulls every marker-free, boundary-safe character out of {@code buf}, leaving behind only an
     * in-progress marker or a trailing fragment that could still become one. Complete markers are
     * dropped outright. Mirrors {@link #scanBuffer} but strips instead of triggering a search.
     */
    private static String drainStrippable(StringBuilder buf) {
        StringBuilder out = new StringBuilder();
        while (true) {
            int openIdx = buf.indexOf(NEEDS_SEARCH_OPEN);
            if (openIdx == -1) {
                int hold = openMarkerPrefixOverlap(buf);
                int safe = buf.length() - hold;
                if (safe > 0) { out.append(buf, 0, safe); buf.delete(0, safe); }
                return out.toString();
            }
            if (openIdx > 0) { out.append(buf, 0, openIdx); buf.delete(0, openIdx); }
            int closeIdx = buf.indexOf(NEEDS_SEARCH_CLOSE, NEEDS_SEARCH_OPEN.length());
            if (closeIdx == -1) return out.toString();   // marker still open -- wait for more tokens
            buf.delete(0, closeIdx + 1);                  // drop the whole marker, keep scanning
        }
    }

    /**
     * Removes every {@code [NEEDS_SEARCH: ...]} marker (and a dangling, never-closed one) from a
     * complete string, returning the trimmed remainder. Final backstop for the non-streaming path.
     */
    public static String stripSearchMarkers(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text);
        int open;
        while ((open = sb.indexOf(NEEDS_SEARCH_OPEN)) != -1) {
            int close = sb.indexOf(NEEDS_SEARCH_CLOSE, open + NEEDS_SEARCH_OPEN.length());
            if (close == -1) { sb.delete(open, sb.length()); break; }
            sb.delete(open, close + 1);
        }
        return sb.toString().trim();
    }

    /**
     * Runs {@link KubernetesDynamicSearcher#searchAndSave} on a
     * {@link Schedulers#boundedElastic()} thread (blocking call, must not run on Netty
     * event-loop), then starts a fresh gpt-oss streaming call with the retrieved docs
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

        final int dynCap = BudgetComputing.dynamicDocCapFor(gpt.budgetInputChars());
        Mono.fromCallable(() -> dynamicSearcher.searchAndSave(convId, searchQuery, dynCap))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(searchResult -> {
                    String dynamicDocs = searchResult.context();
                    if (searchResult.urls() != null && !searchResult.urls().isEmpty()) {
                        sourceUrlsRef.set(searchResult.urls());
                    }
                    // Rebuild the message list with a hard "no more searches" directive on the system
                    // prompt: the second pass already has the docs, so another marker would only loop/leak.
                    List<Map<String, String>> augmented = withSecondPassSystemDirective(originalMessages);
                    if (dynamicDocs != null && !dynamicDocs.isBlank()) {
                        sink.next(StreamChunk.status("search_done",
                                "Documentație găsită. Se generează răspunsul..."));
                        // Clip docs to the leftover window (RAG share) so the second stream stays in num_ctx.
                        augmented.add(Map.of("role", "user", "content",
                                BudgetComputing.dynamicDocsMessage(dynamicDocs, BudgetComputing.dynamicDocBudget(originalMessages, gpt.budgetInputChars()))));
                    } else {
                        logger.info("[stream] Dynamic search returned nothing; asking LLM to continue without it.");
                        sink.next(StreamChunk.status("search_empty",
                                "Căutarea nu a returnat rezultate. Se generează răspunsul..."));
                        augmented.add(Map.of("role", "user",
                                "content",
                                "Search yielded no new results. Please provide your best diagnosis or advice."));
                    }
                    // Defensive backstop: strip any residual [NEEDS_SEARCH:...] from the second stream so
                    // a non-compliant model can never leak the marker to the client (or have it persisted).
                    return stripMarkerStream(gpt.chatStream(augmented));
                })
                .subscribe(token -> sink.next(StreamChunk.token(token)), sink::error, sink::complete);
    }

    //-----------------------------------------------------
    //Function used in non-streaming solve() function from AiEngine.java
    //------------------------------------------------------
    public DynamicRagResult dynamicRagLoopFunction(String conversationId, String assistantText,
                                        List<Map<String, String>> messages){
        // Ablation switch: dynamic search disabled -> no second pass; strip any residual marker.
        if (!ablation.isDynamicSearchEnabled()) {
            return new DynamicRagResult(stripSearchMarkers(assistantText), null);
        }
        List<String> dynamicSourceUrls = null;
        if (assistantText.contains("[NEEDS_SEARCH:")) {
            metrics.recordNeedsSearchTrigger();
            int startIdx = assistantText.indexOf("[NEEDS_SEARCH:") + 14;
            int endIdx = assistantText.indexOf("]", startIdx);
            if (endIdx != -1) {
                String query = assistantText.substring(startIdx, endIdx).trim();
                logger.info("LLM requested dynamic search for: {}", query);

                KubernetesDynamicSearcher.SearchResult searchResult =
                        dynamicSearcher.searchAndSave(conversationId, query, BudgetComputing.dynamicDocCapFor(gpt.budgetInputChars()));
                String dynamicDocsText = searchResult.context();
                if (searchResult.urls() != null && !searchResult.urls().isEmpty()) {
                    dynamicSourceUrls = searchResult.urls();
                }

                // Second-pass copy: same context plus a hard "no more searches" directive on the
                // system prompt, then the marker turn. The caller's list stays untouched.
                List<Map<String, String>> secondPass = withSecondPassSystemDirective(messages);
                secondPass.add(Map.of("role", "assistant", "content", assistantText));
                if (!dynamicDocsText.isBlank()) {
                    // Budget the docs against what's left of the window so the second call cannot
                    // overflow num_ctx (clipped to the RAG share).
                    String docMsg = BudgetComputing.dynamicDocsMessage(dynamicDocsText, BudgetComputing.dynamicDocBudget(secondPass, gpt.budgetInputChars()));
                    secondPass.add(Map.of("role", "user", "content", docMsg));
                    try {
                        logger.info("Sending secondary request to gpt-oss with dynamic docs context...");
                        String raw = gpt.chat(secondPass);
                        if (raw != null) assistantText = stripSearchMarkers(raw);
                    } catch (Exception e) {
                        logger.error("gpt-oss secondary call failed", e);
                    }
                } else {
                    logger.info("Dynamic search found nothing. Asking LLM to continue without it.");
                    secondPass.add(Map.of("role", "user", "content",
                            "Search yielded no new results. Please provide your best diagnosis or advice."));
                    try {
                        String raw = gpt.chat(secondPass);
                        if (raw != null) assistantText = stripSearchMarkers(raw);
                    } catch (Exception e) {
                    }
                }
            }
        }
        return new DynamicRagResult(assistantText, dynamicSourceUrls);
    }
}
