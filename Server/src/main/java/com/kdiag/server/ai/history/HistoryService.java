package com.kdiag.server.ai.history;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kdiag.server.protocol.KdiagModels.Artifact;

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

    // -------------------------------------------------------------------------
    // Artifact bank
    // -------------------------------------------------------------------------

    private static final int BANK_MAX_ENTRIES        = 5;
    private static final int BANK_SUMMARY_CHARS_EACH = 1500;

    private final Map<String, Deque<BankedArtifact>> artifactBank = new ConcurrentHashMap<>();
    private final Map<String, Long> turnCounters = new ConcurrentHashMap<>();

    public record BankedArtifact(String type, String filename, String summary,
                                  long turnNumber, Instant addedAt) {}

    /**
     * Banks artifacts for a conversation. Assigns a monotonically increasing turn number,
     * truncates each summary to BANK_SUMMARY_CHARS_EACH, and evicts oldest entries when
     * the bank exceeds BANK_MAX_ENTRIES. Returns the turn number assigned to these artifacts.
     */
    public long addArtifacts(String conversationId, List<Artifact> artifacts) {
        long turn = turnCounters.merge(conversationId, 1L, Long::sum);

        Deque<BankedArtifact> deque = artifactBank.computeIfAbsent(
                conversationId, k -> new ArrayDeque<>());

        synchronized (deque) {
            for (Artifact a : artifacts) {
                if (a == null || a.getContent() == null || a.getContent().isBlank()) continue;

                String content = a.getContent();
                String summary;
                if (content.length() <= BANK_SUMMARY_CHARS_EACH) {
                    summary = content;
                } else {
                    summary = content.substring(0, BANK_SUMMARY_CHARS_EACH)
                            + "...[truncated, full version was in turn " + turn + "]";
                    //Suffix-ul "...[truncated, full version was in turn N]" — 
                    // îi spune LLM-ului că summary-ul e parțial și unde e originalul. 
                    // E un cue util pentru raționament: dacă întrebarea curentă cere 
                    // detalii care nu sunt în summary, LLM-ul poate să întrebe userul 
                    // „poți să reatașezi logul de la turul 3?".
                }

                String filename = a.getType() + "-" + turn;
                deque.addLast(new BankedArtifact(a.getType(), filename, summary, turn, Instant.now()));

                while (deque.size() > BANK_MAX_ENTRIES) {
                    deque.removeFirst();
                }
            }
        }
        return turn;
    }

    /** Returns all banked artifacts for a conversation, ordered oldest-first. */
    public List<BankedArtifact> getBankedArtifacts(String conversationId) {
        Deque<BankedArtifact> deque = artifactBank.get(conversationId);
        if (deque == null) return List.of();
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    /**
     * Returns banked artifacts whose turn number is strictly less than {@code turn},
     * ordered oldest-first. Used to exclude the current turn from the bank section.
     */
    public List<BankedArtifact> getBankedArtifactsBefore(String conversationId, long turn) {
        Deque<BankedArtifact> deque = artifactBank.get(conversationId);
        if (deque == null) return List.of();
        synchronized (deque) {
            return deque.stream()
                    .filter(ba -> ba.turnNumber() < turn)
                    .collect(Collectors.toList());
        }
    }

    public void clearArtifacts(String conversationId) {
        artifactBank.remove(conversationId);
        turnCounters.remove(conversationId);
    }

    // -------------------------------------------------------------------------
    // Message history
    // -------------------------------------------------------------------------

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
        List<HistoryEntry> h = history.getOrDefault(conversationId, Collections.emptyList());
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
