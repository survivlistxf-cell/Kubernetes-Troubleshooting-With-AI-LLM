package com.kdiag.server.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.kdiag.server.docs.KubernetesDebugDocs;
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
    private final KubernetesDebugDocs docs;

    public AiEngine(OllamaClient ollama, KubernetesDebugDocs docs) {
        this.ollama = ollama;
        this.docs = docs;
    }

    public AiResult solve(String userText, List<Artifact> artifacts) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(userText, artifacts);

        String llm = null;
        try {
            llm = ollama.chat(systemPrompt, userPrompt);
        } catch (Exception e) {
            // fall through to minimal fallback
        }

        String assistantText = (llm == null || llm.isBlank())
                ? fallbackAnswer(userText, artifacts)
                : llm.trim();

        // Keep actions_requested as a future enhancement. For now, return none.
        return new AiResult(assistantText, null);
    }

    private String buildSystemPrompt() {
        return "You are a Kubernetes diagnostics assistant.\n"
                + "You MUST base your advice on Kubernetes best practices and the official debugging workflow.\n"
                + "When you suggest commands, prefer kubectl commands and clearly indicate placeholders.\n"
                + "Be concise, structured, and actionable.\n\n"
                + docs.getGuidanceSnippet();
    }

    private String buildUserPrompt(String userText, List<Artifact> artifacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("User question:\n");
        sb.append(userText == null ? "" : userText);
        sb.append("\n\n");

        if (artifacts != null && !artifacts.isEmpty()) {
            sb.append("Diagnostics artifacts (may contain logs/events/describes):\n");
            int maxArtifacts = Math.min(artifacts.size(), 8);
            for (int i = 0; i < maxArtifacts; i++) {
                Artifact a = artifacts.get(i);
                if (a == null) continue;
                sb.append("--- artifact ").append(i + 1).append(" ---\n");
                sb.append("type: ").append(nullToEmpty(a.getType())).append("\n");
                if (a.getTarget() != null) sb.append("target: ").append(a.getTarget()).append("\n");
                if (a.getContainer() != null) sb.append("container: ").append(a.getContainer()).append("\n");
                sb.append("content:\n");
                sb.append(truncate(a.getContent(), 6000));
                sb.append("\n\n");
            }

            // light summary to help the model
            sb.append("Artifact types: ");
            sb.append(artifacts.stream()
                    .filter(x -> x != null && x.getType() != null)
                    .map(Artifact::getType)
                    .distinct()
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        sb.append("\nReturn a short root-cause hypothesis and a numbered list of next steps.\n");
        return sb.toString();
    }

    private String fallbackAnswer(String userText, List<Artifact> artifacts) {
        String normalized = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        StringBuilder answer = new StringBuilder();
        answer.append("I couldn't reach the LLM right now, but I can still suggest standard Kubernetes debugging steps.\n\n");

        if (normalized.contains("crashloop")) {
            answer.append("Symptom: CrashLoopBackOff\n");
            answer.append("1) `kubectl describe pod <pod> -n <ns>` (check Events + Last State)\n");
            answer.append("2) `kubectl logs <pod> -n <ns> --previous --tail=200`\n");
            answer.append("3) Verify env/config/secret mounts and recent image changes\n");
        } else {
            answer.append("1) `kubectl get pods -n <ns>`\n");
            answer.append("2) `kubectl describe pod <pod> -n <ns>`\n");
            answer.append("3) `kubectl logs <pod> -n <ns> --tail=200` (and `--previous` if restarting)\n");
        }

        answer.append("\n").append(docs.getGuidanceSnippet());
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
