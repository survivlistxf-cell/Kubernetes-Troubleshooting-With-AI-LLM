package com.kdiag.server.ai;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.ollama.OllamaClient;
import com.kdiag.server.protocol.KdiagModels.Artifact;

/**
 * Ollama-backed AI engine.
 *
 * Input: user message + optional artifacts.
 * Output: assistant text + optional actions_requested.
 */
@Service
public class AiEngine {

    private static final Logger logger = LoggerFactory.getLogger(AiEngine.class);
    private static final int MAX_RECENT_HISTORY_MESSAGES     = 12;
    private static final int SUMMARY_TRIGGER_HISTORY_MESSAGES = 10;
    private static final int MAX_RETRIEVAL_SNIPPET_CHARS      = 400;
    private static final int MAX_TOTAL_PROMPT_CHARS           = 28000;
    private static final int MAX_SUMMARY_INPUT_MESSAGES       = 12;
    private static final int MAX_SUMMARY_CHARS                = 1600;
    private static final int NEEDS_SEARCH_BUFFER_CHARS        = 256;
    private static final String NEEDS_SEARCH_OPEN  = "[NEEDS_SEARCH:";
    private static final String NEEDS_SEARCH_CLOSE = "]";

    // Size-based artifact budget constants
    private static final int    MAX_TOTAL_ARTIFACT_CHARS = 15000;
    private static final int    MIN_RAG_CHARS            = 6000;
    private static final int    MAX_RAG_CHARS            = 14000;
    private static final double ARTIFACT_TO_RAG_RATIO    = 0.5;

    private final ExecutorService summaryExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "kdiag-summary-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final OllamaClient ollama;
    private final KubernetesDocsScraper docsScraper;
    private final KubernetesDynamicSearcher dynamicSearcher;
    private final HistoryService historyService;
    private final FeedbackRetrievalService feedbackRetrievalService;
    private final MetricsCollector metrics;

    public AiEngine(OllamaClient ollama, KubernetesDocsScraper docsScraper,
            KubernetesDynamicSearcher dynamicSearcher,
            HistoryService historyService,
            FeedbackRetrievalService feedbackRetrievalService,
            MetricsCollector metrics) {
        this.ollama = ollama;
        this.docsScraper = docsScraper;
        this.dynamicSearcher = dynamicSearcher;
        this.historyService = historyService;
        this.feedbackRetrievalService = feedbackRetrievalService;
        this.metrics = metrics;
    }

    private void debugLog(String msg) {
        try {
            String line = LocalDateTime.now() + " " + msg + "\n";
            Files.write(Paths.get("ai_debug.log"), line.getBytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Artifact budget
    // -------------------------------------------------------------------------

    /** Per-request artifact allocation computed by {@link #computeArtifactBudget}. */
    record ArtifactBudget(int[] perArtifactChars, int totalArtifactChars, int ragChars) {}

    /**
     * FIFO size-based allocation: each artifact gets min(rawLen, remaining capacity).
     * Total artifact chars are capped at MAX_TOTAL_ARTIFACT_CHARS.
     * RAG chars are dynamically reduced by ARTIFACT_TO_RAG_RATIO per artifact char consumed,
     * floored at MIN_RAG_CHARS.
     */
    ArtifactBudget computeArtifactBudget(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return new ArtifactBudget(new int[0], 0, MAX_RAG_CHARS);
        }
        int[] alloc = new int[artifacts.size()];
        int used = 0;
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            int rawLen = (a == null || a.getContent() == null) ? 0 : a.getContent().length();
            int remaining = MAX_TOTAL_ARTIFACT_CHARS - used;
            if (remaining <= 0) { alloc[i] = 0; continue; }
            alloc[i] = Math.min(rawLen, remaining);
            used += alloc[i];
        }
        int ragChars = Math.max(MIN_RAG_CHARS,
                MAX_RAG_CHARS - (int) Math.round(used * ARTIFACT_TO_RAG_RATIO));
        return new ArtifactBudget(alloc, used, ragChars);
    }

    // -------------------------------------------------------------------------
    // solve() — non-streaming
    // -------------------------------------------------------------------------

    public AiResult solve(String conversationId, String userText, List<Artifact> artifacts) {
        final long solveStart = System.currentTimeMillis();
        debugLog("[AiEngine] solve() convId=" + conversationId + " userText=" + userText + " artSize="
                + (artifacts != null ? artifacts.size() : 0));

        // 1. Process and split artifacts (especially .txt files with multiple sections)
        List<Artifact> processedArtifacts = processArtifacts(artifacts);

        // 1a. Compute size-based budget
        ArtifactBudget budget = computeArtifactBudget(processedArtifacts);
        int rawTotal = processedArtifacts.stream()
                .mapToInt(a -> (a == null || a.getContent() == null) ? 0 : a.getContent().length())
                .sum();
        logger.info("Artifact budget: rawTotal={}, allocated={}, ragChars={}, perArtifact={}",
                rawTotal, budget.totalArtifactChars(), budget.ragChars(),
                Arrays.toString(budget.perArtifactChars()));

        // 1b. CBR read: load boosted URLs + retrieve similar past cases
        Set<String> boostedUrls = feedbackRetrievalService.getBoostedUrls();
        List<FeedbackRetrievalService.SimilarCase> similarCases =
                (userText != null && !userText.isBlank())
                        ? feedbackRetrievalService.findSimilarCases(userText)
                        : List.of();

        // 2. Fetch relevant docs (boost-aware, dynamic ragChars)
        String relevantDocs = fetchRelevantDocs(userText, processedArtifacts, boostedUrls,
                budget.ragChars());

        // 3. Build current user message content
        String userContent = buildUserPrompt(userText, processedArtifacts,
                budget.perArtifactChars());

        // 3b. Artifact bank management
        List<HistoryService.BankedArtifact> bank;
        if (conversationId != null) {
            if (!processedArtifacts.isEmpty()) {
                long currentTurn = historyService.addArtifacts(conversationId, processedArtifacts);
                bank = historyService.getBankedArtifactsBefore(conversationId, currentTurn);
            } else {
                bank = historyService.getBankedArtifacts(conversationId);
            }
        } else {
            bank = List.of();
        }

        // 4. Update history with user message
        if (conversationId != null) {
            debugLog("[AiEngine] Adding user turn to history for " + conversationId);
            historyService.addEntry(conversationId, "user", userContent);
            historyService.trimHistoryToLatest(conversationId, MAX_RECENT_HISTORY_MESSAGES);
        }

        // 5. Build full message list for Ollama
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        String conversationSummary = conversationId != null
                ? historyService.getConversationSummary(conversationId) : null;
        boolean isFirstTurn = conversationId == null
                || historyService.getHistory(conversationId).size() <= 1;
        logger.debug("System prompt tier: {} (history size={})",
                isFirstTurn ? "full" : "compact",
                conversationId == null ? -1 : historyService.getHistory(conversationId).size());

        String systemPrompt = buildSystemPrompt(relevantDocs, conversationSummary, similarCases,
                bank, isFirstTurn);
        int remainingBudget = MAX_TOTAL_PROMPT_CHARS;
        messages.add(Map.of("role", "system", "content", systemPrompt));
        remainingBudget -= systemPrompt.length();

        if (conversationId != null) {
            List<HistoryService.HistoryEntry> historyEntries = historyService
                    .getHistory(conversationId);
            debugLog("[AiEngine] History for [" + conversationId + "] has " + historyEntries.size() + " entries.");
            for (int i = 0; i < historyEntries.size(); i++) {
                HistoryService.HistoryEntry entry = historyEntries.get(i);
                String content = entry.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                if (remainingBudget <= 0) {
                    break;
                }
                String clipped = truncateToBudget(content, remainingBudget);
                debugLog("[AiEngine]   - " + entry.role() + ": "
                        + (clipped.length() > 50 ? clipped.substring(0, 50).replace("\n", " ") + "..."
                                : clipped));
                messages.add(Map.of("role", entry.role(), "content", clipped));
                remainingBudget -= clipped.length();
            }
        } else {
            messages.add(Map.of("role", "user", "content", truncateToBudget(userContent, remainingBudget)));
        }

        debugLog("[AiEngine] Final message list size: " + messages.size());

        // Count assembled prompt chars and flag near-overflow early
        final int solvePromptChars = messages.stream()
                .mapToInt(msg -> msg.getOrDefault("content", "").length())
                .sum();
        metrics.recordNumCtxOverflowIfApplicable(solvePromptChars, ollama.getNumCtx());

        String llm = null;
        try {
            logger.info("Sending {} messages to Ollama for conversation {}", messages.size(), conversationId);
            llm = ollama.chat(messages);
        } catch (Exception e) {
            logger.error("Ollama call failed", e);
        }

        final boolean usedFallback = (llm == null || llm.isBlank());
        String assistantText = usedFallback
                ? fallbackAnswer(userText, processedArtifacts, relevantDocs)
                : llm.trim();
        if (usedFallback) metrics.recordFallbackResponse();

        // --- SECONDARY DYNAMIC RAG LOOP ---
        if (assistantText.contains("[NEEDS_SEARCH:")) {
            metrics.recordNeedsSearchTrigger();
            int startIdx = assistantText.indexOf("[NEEDS_SEARCH:") + 14;
            int endIdx = assistantText.indexOf("]", startIdx);
            if (endIdx != -1) {
                String query = assistantText.substring(startIdx, endIdx).trim();
                logger.info("LLM requested dynamic search for: {}", query);

                String dynamicDocsText = dynamicSearcher.searchAndSave(conversationId, query);

                if (!dynamicDocsText.isBlank()) {
                    messages.add(Map.of("role", "assistant", "content", assistantText));
                    messages.add(Map.of("role", "user", "content",
                            "Here is additional documentation from Kubernetes website based on your search:\n\n"
                                    + dynamicDocsText + "\n\nPlease solve the user's issue now."));
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
        // ----------------------------------

        // 6. Save assistant response to history
        if (conversationId != null && assistantText != null) {
            historyService.addEntry(conversationId, "assistant", assistantText);
            historyService.trimHistoryToLatest(conversationId, MAX_RECENT_HISTORY_MESSAGES);
            maybeScheduleConversationSummary(conversationId);

            try {
                feedbackRetrievalService.recordExchange(conversationId, userText, assistantText, null);
            } catch (Exception e) {
                logger.warn("Failed to record QA exchange for {}: {}", conversationId, e.getMessage());
            }
        }

        metrics.recordChatRequest(System.currentTimeMillis() - solveStart,
                solvePromptChars, assistantText.length());
        return new AiResult(assistantText, null);
    }

    // -------------------------------------------------------------------------
    // Streaming path
    // -------------------------------------------------------------------------

    /**
     * Like {@link #solve} but returns a {@link Flux} of token strings for SSE streaming.
     *
     * <p>Setup steps (history, RAG, prompt assembly) run synchronously before subscription.
     * History persistence happens in {@code doFinally} so it fires regardless of how the
     * stream ends (complete / cancel).  On error, {@code onErrorResume} emits the full
     * fallback answer as a single token so the client still sees a useful response.
     *
     * <p>The first {@value #NEEDS_SEARCH_BUFFER_CHARS} characters are buffered internally
     * by {@link #wrapWithDynamicSearchLoop}.  If a {@code [NEEDS_SEARCH: query]} marker is
     * detected the original stream is cancelled, {@link KubernetesDynamicSearcher#searchAndSave}
     * runs on a bounded-elastic thread, and a fresh Ollama stream with the augmented prompt
     * is substituted transparently.  If no marker is found the buffer is flushed and
     * subsequent tokens pass through unchanged.
     */
    public Flux<StreamChunk> solveStream(String conversationId, String userText, List<Artifact> artifacts) {
        final long streamSolveStart = System.currentTimeMillis();
        debugLog("[AiEngine] solveStream() convId=" + conversationId + " userText=" + userText);

        // 1. Artifact processing
        List<Artifact> processedArtifacts = processArtifacts(artifacts);

        // 1a. Compute size-based budget
        ArtifactBudget budget = computeArtifactBudget(processedArtifacts);
        int rawTotal = processedArtifacts.stream()
                .mapToInt(a -> (a == null || a.getContent() == null) ? 0 : a.getContent().length())
                .sum();
        logger.info("Artifact budget: rawTotal={}, allocated={}, ragChars={}, perArtifact={}",
                rawTotal, budget.totalArtifactChars(), budget.ragChars(),
                Arrays.toString(budget.perArtifactChars()));

        // 1b. CBR read: load boosted URLs + retrieve similar past cases
        Set<String> boostedUrls = feedbackRetrievalService.getBoostedUrls();
        List<FeedbackRetrievalService.SimilarCase> similarCases =
                (userText != null && !userText.isBlank())
                        ? feedbackRetrievalService.findSimilarCases(userText)
                        : List.of();

        // 2. RAG context (boost-aware, dynamic ragChars)
        final String relevantDocs = fetchRelevantDocs(userText, processedArtifacts, boostedUrls,
                budget.ragChars());

        // 3. User prompt content
        String userContent = buildUserPrompt(userText, processedArtifacts,
                budget.perArtifactChars());

        // 3b. Artifact bank management
        List<HistoryService.BankedArtifact> bank;
        if (conversationId != null) {
            if (!processedArtifacts.isEmpty()) {
                long currentTurn = historyService.addArtifacts(conversationId, processedArtifacts);
                bank = historyService.getBankedArtifactsBefore(conversationId, currentTurn);
            } else {
                bank = historyService.getBankedArtifacts(conversationId);
            }
        } else {
            bank = List.of();
        }

        // 4. Add user turn to history
        if (conversationId != null) {
            historyService.addEntry(conversationId, "user", userContent);
            historyService.trimHistoryToLatest(conversationId, MAX_RECENT_HISTORY_MESSAGES);
        }

        // 5. Build message list
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        String conversationSummary = conversationId != null
                ? historyService.getConversationSummary(conversationId) : null;
        boolean isFirstTurn = conversationId == null
                || historyService.getHistory(conversationId).size() <= 1;
        logger.debug("System prompt tier: {} (history size={})",
                isFirstTurn ? "full" : "compact",
                conversationId == null ? -1 : historyService.getHistory(conversationId).size());

        String systemPrompt = buildSystemPrompt(relevantDocs, conversationSummary, similarCases,
                bank, isFirstTurn);
        int remainingBudget = MAX_TOTAL_PROMPT_CHARS;
        messages.add(Map.of("role", "system", "content", systemPrompt));
        remainingBudget -= systemPrompt.length();

        if (conversationId != null) {
            for (HistoryService.HistoryEntry entry : historyService.getHistory(conversationId)) {
                String content = entry.content();
                if (content == null || content.isBlank() || remainingBudget <= 0) continue;
                String clipped = truncateToBudget(content, remainingBudget);
                messages.add(Map.of("role", entry.role(), "content", clipped));
                remainingBudget -= clipped.length();
            }
        } else {
            messages.add(Map.of("role", "user",
                    "content", truncateToBudget(userContent, remainingBudget)));
        }

        // Capture finals for lambdas
        final String convId = conversationId;
        final String finalUserText = userText;
        final List<Artifact> finalProcessed = processedArtifacts;
        final StringBuilder buffer = new StringBuilder();

        // Prompt size snapshot (before streaming starts)
        final int streamPromptChars = messages.stream()
                .mapToInt(msg -> msg.getOrDefault("content", "").length())
                .sum();
        metrics.recordNumCtxOverflowIfApplicable(streamPromptChars, ollama.getNumCtx());

        logger.info("Starting streaming Ollama call for conversation {}", convId);

        return wrapWithDynamicSearchLoop(convId, messages, ollama.chatStream(messages))
                .doOnNext(chunk -> {
                    if (chunk.type() == StreamChunk.Type.TOKEN && chunk.text() != null) {
                        buffer.append(chunk.text());
                    }
                })
                .onErrorResume(e -> {
                    logger.error("Streaming Ollama call failed for conversation {}", convId, e);
                    String fallback = fallbackAnswer(finalUserText, finalProcessed, relevantDocs);
                    if (convId != null && !fallback.isBlank()) {
                        historyService.addEntry(convId, "assistant", fallback);
                        historyService.trimHistoryToLatest(convId, MAX_RECENT_HISTORY_MESSAGES);
                        maybeScheduleConversationSummary(convId);
                    }
                    return Flux.just(StreamChunk.token(fallback));
                })
                .doFinally(signal -> {
                    if (signal == SignalType.ON_ERROR) return;
                    String assistantText = buffer.toString().trim();
                    if (!assistantText.isBlank()) {
                        metrics.recordStreamingRequest(
                                System.currentTimeMillis() - streamSolveStart,
                                streamPromptChars,
                                assistantText.length());
                    }
                    if (assistantText.isBlank() || convId == null) return;
                    historyService.addEntry(convId, "assistant", assistantText);
                    historyService.trimHistoryToLatest(convId, MAX_RECENT_HISTORY_MESSAGES);
                    maybeScheduleConversationSummary(convId);
                    try {
                        feedbackRetrievalService.recordExchange(convId, finalUserText, assistantText, null);
                    } catch (Exception e) {
                        logger.warn("Failed to record streaming QA exchange for {}: {}", convId, e.getMessage());
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Streaming NEEDS_SEARCH detection loop
    // -------------------------------------------------------------------------

    /**
     * Wraps {@code firstStream} with a detection window for the
     * {@code [NEEDS_SEARCH: query]} marker.
     *
     * <p>Tokens are buffered silently until either {@value #NEEDS_SEARCH_BUFFER_CHARS}
     * characters have been collected <em>or</em> the close bracket of a found open-marker
     * arrives — whichever comes first.  Then one of two branches executes:
     * <ul>
     *   <li><b>Marker found</b> — the first stream is disposed and
     *       {@link #startSecondStream} is called with the extracted query.</li>
     *   <li><b>No marker</b> — the accumulated buffer is flushed as one token and
     *       subsequent tokens pass through unchanged.</li>
     * </ul>
     */
    private Flux<StreamChunk> wrapWithDynamicSearchLoop(
            String convId,
            List<Map<String, String>> originalMessages,
            Flux<String> firstStream) {

        return Flux.create(sink -> {
            AtomicBoolean decided  = new AtomicBoolean(false);
            AtomicBoolean replaced = new AtomicBoolean(false);
            AtomicReference<Disposable> subRef = new AtomicReference<>();
            StringBuilder buf = new StringBuilder();

            Disposable d = firstStream.subscribe(
                token -> {
                    if (replaced.get()) return;

                    if (decided.get()) {
                        sink.next(StreamChunk.token(token));
                        return;
                    }

                    buf.append(token);

                    int openIdx = buf.indexOf(NEEDS_SEARCH_OPEN);
                    boolean bufFull = buf.length() >= NEEDS_SEARCH_BUFFER_CHARS;
                    boolean markerClosed = openIdx != -1
                            && buf.indexOf(NEEDS_SEARCH_CLOSE, openIdx + NEEDS_SEARCH_OPEN.length()) != -1;

                    if (!bufFull && !markerClosed) return;

                    if (markerClosed) {
                        int closeIdx = buf.indexOf(NEEDS_SEARCH_CLOSE, openIdx + NEEDS_SEARCH_OPEN.length());
                        String query = buf.substring(openIdx + NEEDS_SEARCH_OPEN.length(), closeIdx).trim();
                        decided.set(true);
                        replaced.set(true);
                        Disposable current = subRef.get();
                        if (current != null) current.dispose();
                        startSecondStream(convId, originalMessages, query, sink);
                        return;
                    }

                    decided.set(true);
                    sink.next(StreamChunk.token(buf.toString()));
                },
                e -> { if (!replaced.get()) sink.error(e); },
                () -> {
                    if (replaced.get()) return;
                    if (!decided.get()) {
                        String content = buf.toString();
                        int openIdx = content.indexOf(NEEDS_SEARCH_OPEN);
                        if (openIdx != -1) {
                            int closeIdx = content.indexOf(NEEDS_SEARCH_CLOSE,
                                    openIdx + NEEDS_SEARCH_OPEN.length());
                            if (closeIdx != -1) {
                                String query = content.substring(
                                        openIdx + NEEDS_SEARCH_OPEN.length(), closeIdx).trim();
                                decided.set(true);
                                replaced.set(true);
                                startSecondStream(convId, originalMessages, query, sink);
                                return;
                            }
                        }
                        if (!content.isBlank()) sink.next(StreamChunk.token(content));
                    }
                    sink.complete();
                }
            );
            subRef.set(d);
            sink.onCancel(d::dispose);
            sink.onDispose(d::dispose);
        });
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
                                   FluxSink<StreamChunk> sink) {
        logger.info("[stream] NEEDS_SEARCH triggered, query='{}'", searchQuery);
        metrics.recordNeedsSearchTrigger();

        // Notify the frontend that a dynamic search is in progress.
        sink.next(StreamChunk.status("searching", "Se caută documentație suplimentară..."));

        Mono.fromCallable(() -> dynamicSearcher.searchAndSave(convId, searchQuery))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(dynamicDocs -> {
                    List<Map<String, String>> augmented = new java.util.ArrayList<>(originalMessages);
                    if (dynamicDocs != null && !dynamicDocs.isBlank()) {
                        sink.next(StreamChunk.status("search_done",
                                "Documentație găsită. Se generează răspunsul..."));
                        augmented.add(Map.of("role", "user",
                                "content",
                                "Here is additional documentation from Kubernetes website based on your search:\n\n"
                                        + dynamicDocs
                                        + "\n\nPlease solve the user's issue now."));
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

    // -------------------------------------------------------------------------
    // Artifact processing
    // -------------------------------------------------------------------------

    private List<Artifact> processArtifacts(List<Artifact> artifacts) {
        if (artifacts == null)
            return List.of();
        List<Artifact> result = new java.util.ArrayList<>();
        for (Artifact a : artifacts) {
            if (a.getContent() != null && a.getContent().contains("--- kubectl")) {
                result.addAll(splitStructuredContent(a));
            } else {
                result.add(a);
            }
        }
        return result;
    }

    private List<Artifact> splitStructuredContent(Artifact original) {
        List<Artifact> parts = new java.util.ArrayList<>();
        String content = original.getContent();

        String[] sections = content.split("(?m)^--- ");
        for (String section : sections) {
            if (section.isBlank())
                continue;

            Artifact p = new Artifact();
            if (section.toLowerCase().startsWith("kubectl describe")) {
                p.setType("pod_describe");
                p.setContent(section.substring(section.indexOf("\n") + 1));
            } else if (section.toLowerCase().startsWith("logs") || section.toLowerCase().startsWith("kubectl logs")) {
                p.setType("pod_logs");
                p.setContent(section.substring(section.indexOf("\n") + 1));
            } else if (section.toLowerCase().startsWith("events")) {
                p.setType("pod_events");
                p.setContent(section.substring(section.indexOf("\n") + 1));
            } else {
                p.setType(original.getType());
                p.setContent(section);
            }
            parts.add(p);
        }
        return parts.isEmpty() ? List.of(original) : parts;
    }

    // -------------------------------------------------------------------------
    // RAG and prompt builders
    // -------------------------------------------------------------------------

    private String fetchRelevantDocs(String userText, List<Artifact> artifacts,
                                     Set<String> boostedUrls, int maxRagChars) {
        try {
            StringBuilder query = new StringBuilder(userText == null ? "" : userText);
            if (artifacts != null) {
                for (Artifact a : artifacts) {
                    if (a != null && a.getType() != null) {
                        query.append(" ").append(a.getType());
                    }
                    if (a != null && a.getContent() != null) {
                        String snippet = a.getContent().length() > MAX_RETRIEVAL_SNIPPET_CHARS
                            ? a.getContent().substring(0, MAX_RETRIEVAL_SNIPPET_CHARS)
                                : a.getContent();
                        query.append(" ").append(snippet);
                    }
                }
            }

            String bm25Result = docsScraper.getRelevantDocsByBm25Boosted(
                    query.toString(), maxRagChars, boostedUrls);
            if (!bm25Result.isBlank()) {
                return bm25Result;
            }
            return docsScraper.getRelevantDocs(query.toString());
        } catch (Exception e) {
            logger.error("Failed to fetch docs (continuing without docs)", e);
            return "";
        }
    }

    /**
     * Builds the system prompt.
     * isFirstTurn=true → full verbose preamble.
     * isFirstTurn=false → compact ~200-char preamble; dynamic sections always emitted in full.
     */
    private String buildSystemPrompt(String relevantDocs, String conversationSummary,
                                     List<FeedbackRetrievalService.SimilarCase> similarCases,
                                     List<HistoryService.BankedArtifact> bank,
                                     boolean isFirstTurn) {
        StringBuilder sb = new StringBuilder();

        if (isFirstTurn) {
            sb.append("You are a Kubernetes diagnostics assistant.\n");
            sb.append("Be direct and helpful.\n\n");
            sb.append("IMPORTANT ACTIONS:\n");
            sb.append(
                    "- If user asks to 'stop commands', 'no commands', or 'without commands': analyze only, suggest NO kubectl commands\n");
            sb.append("- If user provides error messages or logs: explain what they mean\n");
            sb.append("- Focus on understanding the problem first, not just solutions\n");
            sb.append(
                    "- DYNAMIC SEARCH: If the current documentation context does not contain enough information to solve a complex or obscure issue, DO NOT invent a solution. Instead, output EXACTLY this string and nothing else: `[NEEDS_SEARCH: <query>]` where `<query>` is the short, exact term you want to search on kubernetes.io (e.g. `[NEEDS_SEARCH: nginx ingress 403 error]`). I will fetch the internet for you and return the documents.\n\n");
        } else {
            sb.append("You are Kubexplain, the Kubernetes diagnostic assistant. Continue this conversation applying the same conventions established earlier: cite sources from the documentation block when referencing facts, output [NEEDS_SEARCH: query] if the available documentation is insufficient, and structure responses in readable markdown.\n\n");
        }

        if (conversationSummary != null && !conversationSummary.isBlank()) {
            sb.append("Conversation summary so far:\n");
            sb.append(conversationSummary.trim());
            sb.append("\n\n");
        }

        // Case-based retrieval hits — positively-rated past Q&A pairs
        if (similarCases != null && !similarCases.isEmpty()) {
            StringBuilder casesBlock = new StringBuilder(
                    "=== PREVIOUSLY SUCCESSFUL ANSWERS (use as guidance) ===\n");
            int budget = 4000;
            for (FeedbackRetrievalService.SimilarCase c : similarCases) {
                if (budget <= 0) break;
                String entry = String.format(Locale.ROOT,
                        "User asked: %s\nAnswer: %s\nSimilarity: %.2f\n---\n",
                        c.userQuestion(), c.aiResponse(), c.similarity());
                if (entry.length() > budget) {
                    entry = entry.substring(0, budget);
                }
                casesBlock.append(entry);
                budget -= entry.length();
            }
            casesBlock.append("==========================================\n\n");
            sb.append(casesBlock);
            debugLog("[AiEngine] Similar cases injected: "
                    + casesBlock.substring(0, Math.min(300, casesBlock.length())));
        }

        // Historical artifact bank (prior turns only — current turn is in the user message)
        if (bank != null && !bank.isEmpty()) {
            sb.append("## Reference artifacts attached earlier in this conversation\n");
            sb.append("(These were uploaded by the user previously. Use them as context only if relevant to the current question.)\n\n");
            for (HistoryService.BankedArtifact ba : bank) {
                sb.append("[turn ").append(ba.turnNumber()).append(" - ")
                  .append(ba.type()).append(" - ")
                  .append(ba.filename()).append("]\n");
                sb.append(ba.summary()).append("\n\n");
            }
        }

        if (relevantDocs != null && !relevantDocs.isBlank()) {
            sb.append("Official Kubernetes docs (from cache):\n");
            sb.append(relevantDocs);
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildUserPrompt(String userText, List<Artifact> artifacts,
                                   int[] perArtifactChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(userText == null ? "" : userText);
        sb.append("\n");

        if (artifacts != null && !artifacts.isEmpty()) {
            StringBuilder artifactSection = new StringBuilder();
            for (int i = 0; i < artifacts.size(); i++) {
                Artifact a = artifacts.get(i);
                if (a == null) continue;
                int alloc = (i < perArtifactChars.length) ? perArtifactChars[i] : 0;
                if (alloc <= 0) continue; // omit artifacts with zero allocation
                artifactSection.append("[").append(a.getType()).append("]\n");
                artifactSection.append(truncate(a.getContent(), alloc)).append("\n\n");
            }
            if (artifactSection.length() > 0) {
                sb.append("\n--- New evidence provided in this turn ---\n");
                sb.append(artifactSection);
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Conversation summary
    // -------------------------------------------------------------------------

    private void maybeScheduleConversationSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        List<HistoryService.HistoryEntry> snapshot = historyService.snapshotHistory(conversationId);
        if (snapshot.size() < SUMMARY_TRIGGER_HISTORY_MESSAGES) {
            return;
        }

        if (!historyService.markSummaryJobInProgress(conversationId)) {
            return;
        }

        final String existingSummary = historyService.getConversationSummary(conversationId);
        final int snapshotSize = snapshot.size();

        CompletableFuture.runAsync(() -> {
            try {
                String summary = generateConversationSummary(existingSummary, snapshot);
                if (summary != null && !summary.isBlank()) {
                    historyService.setConversationSummary(conversationId, summary.trim());
                    historyService.trimHistoryBefore(conversationId, snapshotSize);
                }
            } catch (Exception e) {
                logger.error("Failed to summarize conversation {}", conversationId, e);
            } finally {
                historyService.clearSummaryJobInProgress(conversationId);
            }
        }, summaryExecutor);
    }

    private String generateConversationSummary(String existingSummary,
            List<HistoryService.HistoryEntry> snapshot) {
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "You summarize Kubernetes troubleshooting conversations. Return a concise Romanian summary under 1600 characters. Focus on: user goal, key errors/logs, commands tried, assistant advice already given, and unresolved next steps. Do not invent details."));

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Existing summary (if any):\n");
        userPrompt.append(existingSummary == null || existingSummary.isBlank() ? "<none>" : existingSummary.trim());
        userPrompt.append("\n\nRecent conversation transcript:\n");

        int maxMessages = Math.min(snapshot.size(), MAX_SUMMARY_INPUT_MESSAGES);
        int startIndex = Math.max(0, snapshot.size() - maxMessages);
        for (int i = startIndex; i < snapshot.size(); i++) {
            var entry = snapshot.get(i);
            String content = entry.content() == null ? "" : entry.content().trim();
            if (content.isBlank()) {
                continue;
            }
            userPrompt.append(entry.role().toUpperCase(Locale.ROOT)).append(": ").append(content).append("\n");
        }

        messages.add(Map.of("role", "user", "content", userPrompt.toString()));

        String summary = ollama.chat(messages);
        if (summary == null) {
            return null;
        }
        summary = summary.trim();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }
        return summary;
    }

    // -------------------------------------------------------------------------
    // Fallback answer (when Ollama is unavailable)
    // -------------------------------------------------------------------------

    private String fallbackAnswer(String userText, List<Artifact> artifacts, String relevantDocs) {
        String normalized = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        StringBuilder answer = new StringBuilder();

        boolean avoidCommands = normalized.contains("no command")
                || normalized.contains("stop command")
                || normalized.contains("don't give command")
                || normalized.contains("without command")
                || normalized.contains("no kubectl");

        if (avoidCommands) {
            answer.append("**Diagnosis Analysis** (without commands):\n\n");
            if (normalized.contains("crashloop")) {
                answer.append("**Likely Problem: CrashLoopBackOff**\n\n");
                answer.append("This means your container is crashing repeatedly. Common causes:\n");
                answer.append("- **Application crash**: Code error, missing dependency, or runtime exception\n");
                answer.append("- **Bad environment variables**: Wrong config, missing secrets\n");
                answer.append("- **Incorrect image**: Wrong Docker image or missing entrypoint\n");
                answer.append("- **Resource limits**: Container running out of memory or disk\n\n");
                answer.append("To diagnose: Check the last crash logs for error messages.\n");
            } else if (normalized.contains("pending")) {
                answer.append("**Likely Problem: Pod Stuck in Pending**\n\n");
                answer.append("This usually means Kubernetes can't schedule the pod. Reasons:\n");
                answer.append("- **Insufficient resources**: No nodes have enough CPU/memory\n");
                answer.append("- **Node affinity/taints**: Pod requirements don't match available nodes\n");
                answer.append("- **Storage not available**: PVC can't be bound\n");
                answer.append("- **Image pull error**: Can't download container image\n");
            } else if (normalized.contains("connection") || normalized.contains("database")) {
                answer.append("**Likely Problem: Connection Error**\n\n");
                answer.append("Based on the 'connection' keyword, this could be:\n");
                answer.append(
                        "- **Wrong credentials**: Database password, username, or authentication token changed\n");
                answer.append("- **Network isolation**: Firewall/network policy blocking access\n");
                answer.append("- **Service not running**: Database or backend service is down\n");
                answer.append("- **Wrong hostname/port**: Connection string points to wrong address\n");
                answer.append("- **SSL/TLS issue**: Certificate validation failing\n");
            } else {
                answer.append("**General Diagnosis Approach**\n\n");
                answer.append("Without seeing logs/evidence, I can't pinpoint the exact issue.\n");
                answer.append("However, common deployment problems are:\n");
                answer.append(
                        "- **Configuration mismatch**: Env variables, secrets, or connection strings incorrect\n");
                answer.append(
                        "- **Service dependencies**: Required services (DB, cache, API) not running or unreachable\n");
                answer.append("- **Resource exhaustion**: Application running out of memory, disk, or connections\n");
                answer.append("- **Image/code issues**: Container crashes on startup or during execution\n");
                answer.append("- **Network/security**: Firewall, RBAC, or network policies blocking access\n");
            }
            answer.append("\n").append(relevantDocs != null ? relevantDocs : "");
        } else {
            answer.append("I couldn't reach the LLM, but here's a diagnostic approach:\n\n");
            answer.append("**Step 1: Check Pod Status**\n");
            answer.append("`kubectl get pods -n <ns>` → Look at STATUS and RESTARTS\n\n");

            answer.append("**Step 2: Inspect Events & Logs**\n");
            answer.append("`kubectl describe pod <pod> -n <ns>` → Check Events section for clues\n");
            answer.append("`kubectl logs <pod> -n <ns> --tail=200` → Read container output\n\n");

            answer.append("**Step 3: Analyze the Evidence**\n");
            answer.append(
                    "Look for keywords in logs: 'connection refused', 'timeout', 'authentication', 'not found'\n\n");

            if (relevantDocs != null && !relevantDocs.isBlank()) {
                answer.append(relevantDocs).append("\n");
            }
        }

        return answer.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String truncate(String s, int max) {
        if (s == null)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max) + "\n...[truncated (limit 10k)]";
    }

    private static String truncateToBudget(String s, int maxChars) {
        if (s == null)
            return "";
        if (maxChars <= 0)
            return "";
        if (s.length() <= maxChars)
            return s;
        return s.substring(0, maxChars);
    }

    // -------------------------------------------------------------------------
    // Public result types
    // -------------------------------------------------------------------------

    public static class AiResult {
        private final String assistantText;
        private final List<AiAction> actions;

        public AiResult(String assistantText, List<AiAction> actions) {
            this.assistantText = assistantText;
            this.actions = actions;
        }

        public String getAssistantText() {
            return assistantText;
        }

        public List<AiAction> getActions() {
            return actions;
        }
    }

    public static class AiAction {
        private String id;
        private String type;
        private String collector;
        private Map<String, Object> spec;
        private String why;

        public static AiAction kubectl(String id, String collector, Map<String, Object> spec, String why) {
            AiAction a = new AiAction();
            a.id = id;
            a.type = "collect";
            a.collector = collector;
            a.spec = new HashMap<>(spec);
            a.why = why;
            return a;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getCollector() {
            return collector;
        }

        public Map<String, Object> getSpec() {
            return spec;
        }

        public String getWhy() {
            return why;
        }
    }
}
