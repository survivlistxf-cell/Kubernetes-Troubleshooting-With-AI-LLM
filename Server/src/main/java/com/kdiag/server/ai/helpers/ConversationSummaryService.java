package com.kdiag.server.ai.helpers;

import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.ollama.OllamaClient;

@Service
public class ConversationSummaryService {
    private static final Logger logger = LoggerFactory.getLogger(ConversationSummaryService.class);
    private static final int SUMMARY_TRIGGER_HISTORY_MESSAGES = 10;
    private static final int MAX_SUMMARY_INPUT_MESSAGES       = 12;
    private static final int MAX_SUMMARY_CHARS                = 1600;

    private final HistoryService historyService;
    private final OllamaClient ollama;

    public ConversationSummaryService(HistoryService historyService,
                                OllamaClient ollama
                                    ){
        this.historyService = historyService;
        this.ollama = ollama;
    }

    private final ExecutorService summaryExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "kdiag-summary-worker");
        thread.setDaemon(true);
        return thread;
    });

    // -------------------------------------------------------------------------
    // Conversation summary
    // -------------------------------------------------------------------------

    public void maybeScheduleConversationSummary(String conversationId) {
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
}
