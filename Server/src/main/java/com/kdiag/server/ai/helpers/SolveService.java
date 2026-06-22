package com.kdiag.server.ai.helpers;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.kdiag.server.ai.AiEngine;
import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.llm.GptChatClient;
import com.kdiag.server.protocol.KdiagModels.Artifact;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

@Service
public class SolveService {
    private static final Logger logger = LoggerFactory.getLogger(SolveService.class);
    private static final int MAX_RECENT_HISTORY_MESSAGES     = 12;

    private final GptChatClient gpt;
    private final FeedbackRetrievalService feedbackRetrievalService;
    private final HistoryService historyService;
    private final MetricsCollector metrics;
    private final ConversationSummaryService conversationSummary;
    private final NeedsSearchLoopService needsSearchLoopService;

    public SolveService(GptChatClient gpt, 
                        FeedbackRetrievalService feedbackRetrievalService,
                        HistoryService historyService,
                        MetricsCollector metrics,
                        KubernetesDynamicSearcher dynamicSearcher,
                        ConversationSummaryService conversationSummary,
                        NeedsSearchLoopService needsSearchLoopService){
        this.gpt = gpt;
        this.feedbackRetrievalService = feedbackRetrievalService;
        this.historyService = historyService;
        this.metrics = metrics;
        this.conversationSummary = conversationSummary;
        this.needsSearchLoopService = needsSearchLoopService;
    }

    public record ArtifactProcessingRecord(List<Artifact> processedArtifacts, 
                                            int rawTotal,
                                            Set<String> boosterUrls,
                                            BudgetComputing.ArtifactBudget budget,
                                            List<FeedbackRetrievalService.SimilarCase> similarCases) {}

    public record ArtifactBankRecord(List<HistoryService.BankedTurn> bank, 
                                        List<String> evictedLabels) {}

    public record FullMessageRecord(List<Map<String, String>> messages,
                                            int remainingBudget,
                                            List<HistoryService.HistoryEntry> historyEntries) {}

    //******Step 1 function******
    public ArtifactProcessingRecord artifactProcessing(String userText, List<Artifact> artifacts, 
        FeedbackRetrievalService frs, GptChatClient gpt){
        // 1. Process and split artifacts (especially .txt files with multiple sections)
        // In processedArtifacts pastram doar informatiile relevante (taiem zgomot si caractere
        // folosite inutil).
        List<Artifact> processedArtifacts = ArtifactProcessing.processArtifacts(artifacts, gpt.budgetInputChars());

        // 1a. Compute size-based budget
        //Pe artefactele deja comprimate aplică FIFO size-based: fiecare ia min(rawLen, rămas) 
        // din artifactCapFor; când se epuizează, restul primesc 0. 
        //  RAG-ul scade proporțional (ragMax − 0.5×consumat, floor ragMin).
        BudgetComputing.ArtifactBudget budget = BudgetComputing.computeArtifactBudget(processedArtifacts, gpt.budgetInputChars());
        int rawTotal = processedArtifacts.stream()
                .mapToInt(a -> (a == null || a.getContent() == null) ? 0 : a.getContent().length())
                .sum();

        logger.info("Artifact budget: rawTotal={}, allocated={}, ragChars={}, perArtifact={}",
                rawTotal, budget.totalArtifactChars(), budget.ragChars(),
                Arrays.toString(budget.perArtifactChars()));

        // 1b. CBR read: load boosted URLs + retrieve similar past cases
        // URL-uri boosted prin BM25 pe care le ia din db
        Set<String> boostedUrls = frs.getBoostedUrls();
        // similarCases prin ANN cu embedding pe intrebare si preia intrebarea, URL-ul relevant
        // (daca exista) si raspunsul precedent al AI-ului
        List<FeedbackRetrievalService.SimilarCase> similarCases =
            (userText != null && !userText.isBlank()) 
                ? frs.findSimilarCases(userText) 
                : List.of();
        // Din similarCases ia si URL-urile si le adauga la boostedUrls
        Set<String> effectiveBoost = new java.util.HashSet<>(boostedUrls);
        for (FeedbackRetrievalService.SimilarCase c : similarCases) {
            if (c != null && c.sourceUrls() != null) {
            effectiveBoost.addAll(c.sourceUrls());
            }
        }
        return new ArtifactProcessingRecord(processedArtifacts, rawTotal, effectiveBoost, budget, similarCases);
    }

    //*****Step 3b function *****
    public ArtifactBankRecord artifactBankManagement(String conversationId, List<Artifact> processedArtifacts){
        // Banca de artefacte foloseste din bugetul alocat pentru artefacte, le pastreaza
        // pe cele mai recente, iar cele mai vechi raman summarized in banca (12% din buget)
        // cele foarte vechi raman in evictedLabels pentru a stii LLM-ul ca au fost atasate
        // in conversatie, dar continutul lor nu mai este accesibil
        // Degradare graceful: full → summary → breadcrumb 
        List<HistoryService.BankedTurn> bank;
        List<String> evictedLabels;
        if (conversationId != null) {
            if (!processedArtifacts.isEmpty()) {
                long currentTurn = historyService.addArtifacts(conversationId, processedArtifacts);
                bank = historyService.getBankedTurnsBefore(conversationId, currentTurn);
            } else {
                bank = historyService.getBankedTurns(conversationId);
            }
            evictedLabels = historyService.getEvictedTurnLabels(conversationId);
        } else {
            bank = List.of();
            evictedLabels = List.of();
        }
        return new ArtifactBankRecord(bank, evictedLabels);
    }

    //*****Step 4 function******
    public void addUserMessageToHistory(String conversationId, String userContent){
        // Adauga mesajele in istoric, maxim 12 (6 schimburi AI - user), banca de artefcate necesara
        // pentru a se pastra artefactele daca schimbul de mesaje este peste 12
        if (conversationId != null) {
            logger.info("Adding user turn to history for " + conversationId);
            historyService.addEntry(conversationId, "user", userContent);
            historyService.trimHistoryToLatest(conversationId, MAX_RECENT_HISTORY_MESSAGES);
        }
    }

    // ***** Step 5 function ******
    public FullMessageRecord buildFullMessage(String conversationId, String relevantDocs, 
                                                List<FeedbackRetrievalService.SimilarCase> similarCases,
                                                List<HistoryService.BankedTurn> bank,
                                                List<String> evictedLabels,
                                                BudgetComputing.ArtifactBudget budget){
        // Se construieste intregul mesaj care va fi trimis la gpt:
        // Un summary al conversatiei
        // Istoricul (pana la 12 mesaje)
        // System Prompt-ul tiered (daca e primul tur i se dau mai multe indicatii, daca nu, mai putine)
        // System Prompt-ul contine si relevantDocs si similarCases si banka de artefacte 
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        String conversationSummaryText = conversationId != null
                ? historyService.getConversationSummary(conversationId) : null;
        List<HistoryService.HistoryEntry> historyEntries = conversationId != null
                ? historyService.getHistory(conversationId) : List.of();
        boolean isFirstTurn = conversationId == null || historyEntries.size() <= 1;
        logger.debug("System prompt tier: {} (history size={})",
                isFirstTurn ? "full" : "compact",
                conversationId == null ? -1 : historyEntries.size());

        String systemPrompt = PromptsBuilder.buildSystemPrompt(relevantDocs, conversationSummaryText, similarCases,
                bank, evictedLabels, budget, isFirstTurn, gpt.budgetInputChars());
        int remainingBudget = gpt.budgetInputChars();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        remainingBudget -= systemPrompt.length();

        return new FullMessageRecord(messages, remainingBudget, historyEntries);
    }

    // *****Step 5b function*****
    public void addHistoryMessageToPrompt(String conversationId, int remainingBudget,
                                    List<Map<String, String>> messages, String userContent,
                                    List<HistoryService.HistoryEntry> historyEntries) {
        if (conversationId != null) {
            logger.debug("History for [{}] has {} entries.", conversationId, historyEntries.size());
            for (int i = 0; i < historyEntries.size(); i++) {
                HistoryService.HistoryEntry entry = historyEntries.get(i);
                String content = entry.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                if (remainingBudget <= 0) {
                    break;
                }
                String clipped = BudgetComputing.truncateToBudget(content, remainingBudget);
                logger.debug("   - {}: {}", entry.role(),
                        clipped.length() > 50 ? clipped.substring(0, 50).replace("\n", " ") + "..." : clipped);
                messages.add(Map.of("role", entry.role(), "content", clipped));
                remainingBudget -= clipped.length();
            }
        } else {
            messages.add(Map.of("role", "user", "content", BudgetComputing.truncateToBudget(userContent, remainingBudget)));
        }
        logger.debug("Final message list size: {}", messages.size());
    }

    //****Step 5c function******
    public String callChat(String conversationId, List<Map<String, String>> messages, String userText,
                    List<Artifact> processedArtifacts,
                    String relevantDocs){
        String llm = null;
        try {
            logger.info("Sending {} messages to gpt-oss for conversation {}", messages.size(), conversationId);
            llm = gpt.chat(messages);
        } catch (Exception e) {
            logger.error("gpt-oss chat call failed", e);
        }

        final boolean usedFallback = (llm == null || llm.isBlank());
        String assistantText = usedFallback
                ? AiEngine.fallbackAnswer(userText, processedArtifacts, relevantDocs)
                : llm.trim();
        if (usedFallback) metrics.recordFallbackResponse();
        return assistantText;
    }

    //*****Step 6 function********/
    public void saveAssistantResponseToHistory(String conversationId, String assistantText,
                                        boolean recordExchange,  List<String> dynamicSourceUrls,
                                        String userText, long solveStart, int solvePromptChars){
        if (conversationId != null && assistantText != null) {
            historyService.addEntry(conversationId, "assistant", assistantText);
            historyService.trimHistoryToLatest(conversationId, MAX_RECENT_HISTORY_MESSAGES);
            conversationSummary.maybeScheduleConversationSummary(conversationId);

            if (recordExchange) {
                try {
                    String sourceUrls = (dynamicSourceUrls != null && !dynamicSourceUrls.isEmpty())
                            ? String.join("\n", dynamicSourceUrls) : null;
                    feedbackRetrievalService.recordExchange(conversationId, userText, assistantText, sourceUrls);
                } catch (Exception e) {
                    logger.warn("Failed to record QA exchange for {}: {}", conversationId, e.getMessage());
                }
            }
        }

        metrics.recordChatRequest(System.currentTimeMillis() - solveStart,
                solvePromptChars, assistantText.length());
    }

    // final function for solve streaming mode
    public Flux<StreamChunk> streamFluxFunction(String conversationId,
                                            String userText,
                                            List<Artifact> processedArtifacts,
                                            List<Map<String, String>> messages,
                                            String relevantDocs,
                                            long streamSolveStart,
                                            boolean recordExchange){
        // Capture finals for lambdas
        final String convId = conversationId;
        final String finalUserText = userText;
        final List<Artifact> finalProcessed = processedArtifacts;
        final StringBuilder buffer = new StringBuilder();

        // Prompt size snapshot (before streaming starts)
        final int streamPromptChars = messages.stream()
                .mapToInt(msg -> msg.getOrDefault("content", "").length())
                .sum();
        metrics.recordNumCtxOverflowIfApplicable(streamPromptChars, gpt.getNumCtx());

        logger.info("Starting streaming gpt-oss call for conversation {}", convId);

        final AtomicReference<List<String>> sourceUrlsRef = new AtomicReference<>();

        return needsSearchLoopService.wrapWithDynamicSearchLoop(convId, messages, gpt.chatStream(messages), sourceUrlsRef)
                .doOnNext(chunk -> {
                    if (chunk.type() == StreamChunk.Type.TOKEN && chunk.text() != null) {
                        buffer.append(chunk.text());
                    }
                })
                .onErrorResume(e -> {
                    logger.error("Streaming gpt-oss call failed for conversation {}", convId, e);
                    String fallback = AiEngine.fallbackAnswer(finalUserText, finalProcessed, relevantDocs);
                    if (convId != null && !fallback.isBlank()) {
                        historyService.addEntry(convId, "assistant", fallback);
                        historyService.trimHistoryToLatest(convId, MAX_RECENT_HISTORY_MESSAGES);
                        conversationSummary.maybeScheduleConversationSummary(convId);
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
                    conversationSummary.maybeScheduleConversationSummary(convId);
                    if (recordExchange) {
                        try {
                            List<String> urls = sourceUrlsRef.get();
                            String sourceUrls = (urls != null && !urls.isEmpty())
                                    ? String.join("\n", urls) : null;
                            feedbackRetrievalService.recordExchange(convId, finalUserText, assistantText, sourceUrls);
                        } catch (Exception e) {
                            logger.warn("Failed to record streaming QA exchange for {}: {}", convId, e.getMessage());
                        }
                    }
                });
    }
}
