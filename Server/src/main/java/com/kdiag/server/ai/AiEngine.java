package com.kdiag.server.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.kdiag.server.docs.KubernetesDocsScraper;
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

    private final OllamaClient ollama;
    private final KubernetesDocsScraper docsScraper;

    public AiEngine(OllamaClient ollama, KubernetesDocsScraper docsScraper) {
        this.ollama = ollama;
        this.docsScraper = docsScraper;
    }

    public AiResult solve(String userText, List<Artifact> artifacts) {
        // Fetch relevant docs for THIS specific message
        String relevantDocs = fetchRelevantDocs(userText, artifacts);

        String systemPrompt = buildSystemPrompt(relevantDocs);
        String userPrompt = buildUserPrompt(userText, artifacts);

        String llm = null;
        try {
            llm = ollama.chat(systemPrompt, userPrompt);
        } catch (Exception e) {
            System.err.println("[AiEngine] Ollama call failed: " + e.getMessage());
        }

        String assistantText = (llm == null || llm.isBlank())
                ? fallbackAnswer(userText, artifacts, relevantDocs)
                : llm.trim();

        return new AiResult(assistantText, null);
    }

    private String fetchRelevantDocs(String userText, List<Artifact> artifacts) {
        try {
            StringBuilder query = new StringBuilder(userText == null ? "" : userText);
            if (artifacts != null) {
                for (Artifact a : artifacts) {
                    if (a != null && a.getType() != null) {
                        query.append(" ").append(a.getType());
                    }
                    if (a != null && a.getContent() != null) {
                        String snippet = a.getContent().length() > 200
                                ? a.getContent().substring(0, 200)
                                : a.getContent();
                        query.append(" ").append(snippet);
                    }
                }
            }
            return docsScraper.getRelevantDocs(query.toString());
        } catch (Exception e) {
            System.err.println("[AiEngine] Failed to fetch docs (continuing without docs): " + e.getMessage());
            return "";  // ← Continuă fără docs în loc să arunce eroare
        }
    }

    private String buildSystemPrompt(String relevantDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Kubernetes diagnostics assistant.\n");
        sb.append("Answer in Romanian when user writes in Romanian, English when user writes in English.\n");
        sb.append("Be direct and helpful.\n\n");

        sb.append("IMPORTANT:\n");
        sb.append("- If user asks to 'stop commands', 'no commands', or 'without commands': analyze only, suggest NO kubectl commands\n");
        sb.append("- If user provides error messages or logs: explain what they mean\n");
        sb.append("- Focus on understanding the problem first, not just solutions\n\n");

        if (relevantDocs != null && !relevantDocs.isBlank()) {
            sb.append("Official Kubernetes docs:\n");
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
            sb.append("\n--- Context from scanner ---\n");
            int maxArtifacts = Math.min(artifacts.size(), 5);
            for (int i = 0; i < maxArtifacts; i++) {
                Artifact a = artifacts.get(i);
                if (a == null) continue;
                sb.append("[").append(a.getType()).append("]\n");
                sb.append(truncate(a.getContent(), 3000)).append("\n\n");
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
                answer.append("- **Wrong credentials**: Database password, username, or authentication token changed\n");
                answer.append("- **Network isolation**: Firewall/network policy blocking access\n");
                answer.append("- **Service not running**: Database or backend service is down\n");
                answer.append("- **Wrong hostname/port**: Connection string points to wrong address\n");
                answer.append("- **SSL/TLS issue**: Certificate validation failing\n");
            } else {
                answer.append("**General Diagnosis Approach**\n\n");
                answer.append("Without seeing logs/evidence, I can't pinpoint the exact issue.\n");
                answer.append("However, common deployment problems are:\n");
                answer.append("- **Configuration mismatch**: Env variables, secrets, or connection strings incorrect\n");
                answer.append("- **Service dependencies**: Required services (DB, cache, API) not running or unreachable\n");
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
            answer.append("Look for keywords in logs: 'connection refused', 'timeout', 'authentication', 'not found'\n\n");
            
            if (relevantDocs != null && !relevantDocs.isBlank()) {
                answer.append(relevantDocs).append("\n");
            }
        }
        
        return answer.toString().trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...[truncated]";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static class AiResult {
        private final String assistantText;
        private final List<AiAction> actions;

        public AiResult(String assistantText, List<AiAction> actions) {
            this.assistantText = assistantText;
            this.actions = actions;
        }

        public String getAssistantText() { return assistantText; }
        public List<AiAction> getActions() { return actions; }
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

        public String getId() { return id; }
        public String getType() { return type; }
        public String getCollector() { return collector; }
        public Map<String, Object> getSpec() { return spec; }
        public String getWhy() { return why; }
    }
}
