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

    // One bank entry per TURN (not per artifact), holding the FULL normalized content of every
    // artifact attached in that turn. Older turns beyond BANK_MAX_TURNS are evicted, but their
    // labels (filenames) are kept as breadcrumbs so the LLM still knows what existed and can ask
    // the user to re-attach. The actual char budget for rendering is applied later, in AiEngine.
    private static final int BANK_MAX_TURNS     = 15;
    private static final int EVICTED_LABELS_MAX = 40;

    private final Map<String, Deque<BankedTurn>> artifactBank      = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>>     evictedTurnLabels = new ConcurrentHashMap<>();
    private final Map<String, Long>              turnCounters      = new ConcurrentHashMap<>();

    /** A whole turn's worth of attached artifacts, with full normalized content. */
    public record BankedTurn(long turnNumber, String label, String content, Instant addedAt) {}

    private static String artifactName(Artifact a) {
        String target = a.getTarget();
        return (target != null && !target.isBlank()) ? a.getType() + "@" + target : a.getType();
    }

    /**
     * Banks one entry for the current turn, concatenating the full content of every artifact in
     * the turn (each prefixed with its [name]). Assigns a monotonically increasing turn number,
     * evicts oldest turns beyond BANK_MAX_TURNS (keeping their labels as breadcrumbs), and returns
     * the assigned turn number. Content is stored in full — truncation/summarization happens at
     * render time against the dynamic bank budget.
     */
    public long addArtifacts(String conversationId, List<Artifact> artifacts) {
        long turn = turnCounters.merge(conversationId, 1L, Long::sum);

        StringBuilder labels  = new StringBuilder();
        StringBuilder content = new StringBuilder();
        if (artifacts != null) {
            for (Artifact a : artifacts) {
                if (a == null || a.getContent() == null || a.getContent().isBlank()) continue;
                String name = artifactName(a);
                if (labels.length() > 0) labels.append(", ");
                labels.append(name);
                content.append("[").append(name).append("]\n")
                       .append(a.getContent()).append("\n\n");
            }
        }
        if (content.length() == 0) return turn; // nothing bankable this turn

        Deque<BankedTurn> deque = artifactBank.computeIfAbsent(
                conversationId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new BankedTurn(turn, labels.toString(),
                    content.toString().strip(), Instant.now()));
            while (deque.size() > BANK_MAX_TURNS) {
                recordEvicted(conversationId, deque.removeFirst());
            }
        }
        return turn;
    }

    private void recordEvicted(String conversationId, BankedTurn evicted) {
        Deque<String> ev = evictedTurnLabels.computeIfAbsent(conversationId, k -> new ArrayDeque<>());
        synchronized (ev) {
            ev.addLast("turn " + evicted.turnNumber() + ": " + evicted.label());
            while (ev.size() > EVICTED_LABELS_MAX) ev.removeFirst();
        }
    }

    /** Returns all banked turns for a conversation, ordered oldest-first. */
    public List<BankedTurn> getBankedTurns(String conversationId) {
        Deque<BankedTurn> deque = artifactBank.get(conversationId);
        if (deque == null) return List.of();
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    /**
     * Returns banked turns whose turn number is strictly less than {@code turn}, ordered
     * oldest-first. Used to exclude the current turn from the bank section (its artifacts are
     * already in the current user message).
     */
    public List<BankedTurn> getBankedTurnsBefore(String conversationId, long turn) {
        Deque<BankedTurn> deque = artifactBank.get(conversationId);
        if (deque == null) return List.of();
        synchronized (deque) {
            return deque.stream()
                    .filter(t -> t.turnNumber() < turn)
                    .collect(Collectors.toList());
        }
    }

    /** Labels (filenames) of turns evicted from the bank — breadcrumbs, oldest-first. */
    public List<String> getEvictedTurnLabels(String conversationId) {
        Deque<String> ev = evictedTurnLabels.get(conversationId);
        if (ev == null) return List.of();
        synchronized (ev) {
            return List.copyOf(ev);
        }
    }

    public void clearArtifacts(String conversationId) {
        artifactBank.remove(conversationId);
        evictedTurnLabels.remove(conversationId);
        turnCounters.remove(conversationId);
    }

    // -------------------------------------------------------------------------
    // Message history
    // -------------------------------------------------------------------------

    public void addEntry(String conversationId, String role, String content) {
        List<HistoryEntry> entries = history.computeIfAbsent(conversationId, k -> Collections.synchronizedList(new java.util.ArrayList<>()));
        entries.add(new HistoryEntry(role, content));
        logger.debug("Added {} to [{}]. Current conversation history size: {}", role, conversationId, entries.size());
        logger.debug("Active conversation IDs: {}", history.keySet());
    }

    /**
     * Persists an assistant turn: appends it, then trims to the most recent {@code maxRecent}
     * entries. Summary scheduling and feedback recording stay in the caller (AiEngine), since
     * those are LLM / feedback concerns rather than plain history bookkeeping.
     */
    public void appendAssistant(String conversationId, String content, int maxRecent) {
        addEntry(conversationId, "assistant", content);
        trimHistoryToLatest(conversationId, maxRecent);
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
        logger.debug("Retrieval for {} found {} entries", conversationId, h.size());
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
