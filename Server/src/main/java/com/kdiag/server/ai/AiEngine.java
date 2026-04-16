package com.kdiag.server.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
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
    private final OllamaClient ollama;
    private final KubernetesDocsScraper docsScraper;
    private final KubernetesDynamicSearcher dynamicSearcher;
    private final com.kdiag.server.ai.history.HistoryService historyService;

    public AiEngine(OllamaClient ollama, KubernetesDocsScraper docsScraper, KubernetesDynamicSearcher dynamicSearcher,
            com.kdiag.server.ai.history.HistoryService historyService) {
        this.ollama = ollama;
        this.docsScraper = docsScraper;
        this.dynamicSearcher = dynamicSearcher;
        this.historyService = historyService;
    }

    private void debugLog(String msg) {
        try {
            String line = LocalDateTime.now() + " " + msg + "\n";
            Files.write(Paths.get("ai_debug.log"), line.getBytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public AiResult solve(String conversationId, String userText, List<Artifact> artifacts) {
        debugLog("[AiEngine] solve() convId=" + conversationId + " userText=" + userText + " artSize="
                + (artifacts != null ? artifacts.size() : 0));
        // 1. Process and split artifacts (especially .txt files with multiple sections)
        List<Artifact> processedArtifacts = processArtifacts(artifacts);

        // 2. Fetch relevant docs
        String relevantDocs = fetchRelevantDocs(userText, processedArtifacts);

        // 3. Build current user message content
        String userContent = buildUserPrompt(userText, processedArtifacts);

        // 4. Update history with user message
        if (conversationId != null) {
            debugLog("[AiEngine] Adding user turn to history for " + conversationId);
            historyService.addEntry(conversationId, "user", userContent); // memorie pe termen scurt (cache backend)
        }

        // 5. Build full message list for Ollama
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        String systemPrompt = buildSystemPrompt(relevantDocs);
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (conversationId != null) {
            List<com.kdiag.server.ai.history.HistoryService.HistoryEntry> historyEntries = historyService
                    .getHistory(conversationId); // extragem toate mesajele anterioare din cache
            debugLog("[AiEngine] History for [" + conversationId + "] has " + historyEntries.size() + " entries.");
            historyEntries.forEach(entry -> {
                debugLog("[AiEngine]   - " + entry.role() + ": "
                        + (entry.content().length() > 50 ? entry.content().substring(0, 50).replace("\n", " ") + "..."
                                : entry.content()));
                messages.add(Map.of("role", entry.role(), "content", entry.content()));
            });
        } else {
            messages.add(Map.of("role", "user", "content", userContent));
        }

        debugLog("[AiEngine] Final message list size: " + messages.size());

        String llm = null;
        try {
            logger.info("Sending {} messages to Ollama for conversation {}", messages.size(), conversationId);
            llm = ollama.chat(messages);
        } catch (Exception e) {
            logger.error("Ollama call failed", e);
        }

        String assistantText = (llm == null || llm.isBlank())
                ? fallbackAnswer(userText, processedArtifacts, relevantDocs)
                : llm.trim();

        // --- SECONDARY DYNAMIC RAG LOOP ---
        if (assistantText.contains("[NEEDS_SEARCH:")) {
            int startIdx = assistantText.indexOf("[NEEDS_SEARCH:") + 14;
            int endIdx = assistantText.indexOf("]", startIdx);
            if (endIdx != -1) {
                String query = assistantText.substring(startIdx, endIdx).trim();
                logger.info("LLM requested dynamic search for: {}", query);

                String dynamicDocsText = dynamicSearcher.searchAndSave(conversationId, query);

                if (!dynamicDocsText.isBlank()) {
                    // Update context and query again
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
        }

        return new AiResult(assistantText, null);
    }

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

        // Simple regex split based on Common patterns like --- kubectl describe --- or
        // --- Logs ---
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

    private String fetchRelevantDocs(String userText, List<Artifact> artifacts) {
        try {
            StringBuilder query = new StringBuilder(userText == null ? "" : userText);
            if (artifacts != null) {
                for (Artifact a : artifacts) {
                    if (a != null && a.getType() != null) {
                        query.append(" ").append(a.getType());
                    }
                    // creeaza un sir lung de text (max 200 caractere) in care pune cuvinte cheie
                    // din fiecare fisier atasat + user text
                    if (a != null && a.getContent() != null) {
                        String snippet = a.getContent().length() > 200
                                ? a.getContent().substring(0, 200)
                                : a.getContent();
                        query.append(" ").append(snippet);
                    }
                }
            }
            // query = "De ce crashuieste podul meu? pod_logs OOMKilled: container exceeded
            return docsScraper.getRelevantDocs(query.toString());
        } catch (Exception e) {
            logger.error("Failed to fetch docs (continuing without docs)", e);
            return ""; // ← Continuă fără docs în loc să arunce eroare
        }
    }

    private String buildSystemPrompt(String relevantDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Kubernetes diagnostics assistant.\n");
        sb.append("Be direct and helpful.\n\n");

        sb.append("IMPORTANT ACTIONS:\n");
        sb.append(
                "- If user asks to 'stop commands', 'no commands', or 'without commands': analyze only, suggest NO kubectl commands\n");
        sb.append("- If user provides error messages or logs: explain what they mean\n");
        sb.append("- Focus on understanding the problem first, not just solutions\n");
        sb.append(
                "- DYNAMIC SEARCH: If the current documentation context does not contain enough information to solve a complex or obscure issue, DO NOT invent a solution. Instead, output EXACTLY this string and nothing else: `[NEEDS_SEARCH: <query>]` where `<query>` is the short, exact term you want to search on kubernetes.io (e.g. `[NEEDS_SEARCH: nginx ingress 403 error]`). I will fetch the internet for you and return the documents.\n\n");

        if (relevantDocs != null && !relevantDocs.isBlank()) {
            sb.append("Official Kubernetes docs (from cache):\n");
            sb.append(relevantDocs);
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildUserPrompt(String userText, List<Artifact> artifacts) {
        StringBuilder sb = new StringBuilder();
        sb.append(userText == null ? "" : userText);
        sb.append("\n");

        if (artifacts != null && !artifacts.isEmpty()) {
            sb.append("\n--- New evidence provided in this turn ---\n");
            int maxArtifacts = Math.min(artifacts.size(), 10);
            for (int i = 0; i < maxArtifacts; i++) {
                Artifact a = artifacts.get(i);
                if (a == null)
                    continue;
                sb.append("[").append(a.getType()).append("]\n");
                sb.append(truncate(a.getContent(), 10000)).append("\n\n");
            }
        }

        return sb.toString();
    }

    private String fallbackAnswer(String userText, List<Artifact> artifacts, String relevantDocs) {
        String normalized = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        StringBuilder answer = new StringBuilder();

        // Check if user is asking to avoid commands
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
            // Standard fallback with commands
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

    private static String truncate(String s, int max) {
        if (s == null)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max) + "\n...[truncated (limit 10k)]";
    }

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
