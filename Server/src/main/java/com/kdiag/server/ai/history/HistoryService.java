package com.kdiag.server.ai.history;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory history service for Kubernetes diagnostic chats.
 * Keeps track of messages and context per conversation.
 */
@Service
public class HistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);
    private final Map<String, List<HistoryEntry>> history = new ConcurrentHashMap<>();
    private final Map<String, String> conversationSummaries = new ConcurrentHashMap<>();
    private final Set<String> summaryJobsInProgress = ConcurrentHashMap.newKeySet();

    public void addEntry(String conversationId, String role, String content) {
        List<HistoryEntry> entries = history.computeIfAbsent(conversationId, k -> Collections.synchronizedList(new java.util.ArrayList<>()));
        entries.add(new HistoryEntry(role, content));
        logger.info("Added {} to [{}]. Current conversation history size: {}", role, conversationId, entries.size());
        logger.info("Active conversation IDs: {}", history.keySet());
    }

    public List<HistoryEntry> snapshotHistory(String conversationId) {
        List<HistoryEntry> entries = history.get(conversationId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public void trimHistoryToLatest(String conversationId, int maxEntries) {
        if (maxEntries <= 0) {
            clearHistory(conversationId);
            return;
        }

        List<HistoryEntry> entries = history.get(conversationId);
        if (entries == null) {
            return;
        }

        synchronized (entries) {
            int overflow = entries.size() - maxEntries;
            if (overflow > 0) {
                entries.subList(0, overflow).clear();
            }
        }
    }

    public void trimHistoryBefore(String conversationId, int keepFromIndex) {
        if (keepFromIndex <= 0) {
            return;
        }

        List<HistoryEntry> entries = history.get(conversationId);
        if (entries == null) {
            return;
        }

        synchronized (entries) {
            int removeCount = Math.min(keepFromIndex, entries.size());
            if (removeCount > 0) {
                entries.subList(0, removeCount).clear();
            }
        }
    }

    public String getConversationSummary(String conversationId) {
        return conversationSummaries.get(conversationId);
    }

    public void setConversationSummary(String conversationId, String summary) {
        if (summary == null || summary.isBlank()) {
            conversationSummaries.remove(conversationId);
        } else {
            conversationSummaries.put(conversationId, summary);
        }
    }

    public boolean markSummaryJobInProgress(String conversationId) {
        return summaryJobsInProgress.add(conversationId);
    }

    public void clearSummaryJobInProgress(String conversationId) {
        summaryJobsInProgress.remove(conversationId);
    }

    public List<HistoryEntry> getHistory(String conversationId) {
        List<HistoryEntry> h = history.getOrDefault(conversationId, Collections.emptyList()); // Changed List.of() to Collections.emptyList()
        logger.info("Retrieval for {} found {} entries", conversationId, h.size());
        return h;
    }

    public Set<String> getActiveIds() {
        return history.keySet();
    }

    public void clearHistory(String conversationId) {
        history.remove(conversationId);
        conversationSummaries.remove(conversationId);
        summaryJobsInProgress.remove(conversationId);
    }

    public record HistoryEntry(String role, String content) {}
}
