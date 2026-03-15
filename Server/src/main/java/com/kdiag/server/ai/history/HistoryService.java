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

    public void addEntry(String conversationId, String role, String content) {
        List<HistoryEntry> entries = history.computeIfAbsent(conversationId, k -> Collections.synchronizedList(new java.util.ArrayList<>()));
        entries.add(new HistoryEntry(role, content));
        logger.info("Added {} to [{}]. Current conversation history size: {}", role, conversationId, entries.size());
        logger.info("Active conversation IDs: {}", history.keySet());
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
    }

    public record HistoryEntry(String role, String content) {}
}
