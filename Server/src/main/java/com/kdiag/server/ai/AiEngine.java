package com.kdiag.server.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ai.helpers.BudgetComputing;
import com.kdiag.server.ai.helpers.ConversationSummaryService;
import com.kdiag.server.ai.helpers.NeedsSearchLoopService;
import com.kdiag.server.ai.helpers.PromptsBuilder;
import com.kdiag.server.ai.helpers.SolveService;
import com.kdiag.server.ai.helpers.SolveService.ArtifactBankRecord;
import com.kdiag.server.ai.helpers.SolveService.ArtifactProcessingRecord;
import com.kdiag.server.ai.helpers.SolveService.FullMessageRecord;
import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.config.AblationConfig;
import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.docs.KubernetesDynamicSearcher;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.llm.GptChatClient;
import com.kdiag.server.protocol.KdiagModels.Artifact;

/**
 * LLM-backed AI engine (chat via gpt-oss, OpenAI-compatible).
 *
 * Input: user message + optional artifacts.
 * Output: assistant text + optional actions_requested.
 */
@Service
public class AiEngine {

    private static final Logger logger = LoggerFactory.getLogger(AiEngine.class);

    private final GptChatClient gpt;
    private final KubernetesDocsScraper docsScraper;
    private final FeedbackRetrievalService feedbackRetrievalService;
    private final MetricsCollector metrics;
    private final NeedsSearchLoopService needsSearchLoopService;
    private final SolveService solveService;
    private final AblationConfig ablation;
    private final KubernetesDynamicSearcher dynamicSearcher;

    public AiEngine(GptChatClient gpt, KubernetesDocsScraper docsScraper,
            FeedbackRetrievalService feedbackRetrievalService,
            MetricsCollector metrics,
            NeedsSearchLoopService needsSearchLoopService,
            ConversationSummaryService conversationSummary,
            SolveService solveService,
            AblationConfig ablation,
            KubernetesDynamicSearcher dynamicSearcher) {
        this.gpt = gpt;
        this.docsScraper = docsScraper;
        this.feedbackRetrievalService = feedbackRetrievalService;
        this.metrics = metrics;
        this.needsSearchLoopService = needsSearchLoopService;
        this.solveService = solveService;
        this.ablation = ablation;
        this.dynamicSearcher = dynamicSearcher;
    }

    // -------------------------------------------------------------------------
    // Proactive dynamic fetch (server-side, deterministic)
    // -------------------------------------------------------------------------

    private record ProactiveFetch(String docs, List<String> urls) {}

    /**
     * Server-side dynamic documentation fetch, triggered when the relevance gate
     * decided the static knowledge base has nothing usable for this question.
     *
     * <p>The [NEEDS_SEARCH:] marker remains available to the model as a secondary
     * mechanism, but the primary trigger is deterministic: relying on the LLM to
     * emit the marker proved unreliable in evaluation (gpt-oss consistently chose
     * to answer from parametric memory even when instructed otherwise). Detecting
     * the gap from retrieval scores and searching before the first LLM call removes
     * that dependency entirely.
     *
     * <p>Counts as a NEEDS_SEARCH trigger in metrics (same semantics: a live
     * documentation search caused by missing local knowledge).
     */
    private ProactiveFetch proactiveDynamicFetch(String conversationId, String userText) {
        try {
            logger.info("Relevance gate returned no context — proactive dynamic search for: {}",
                    userText != null && userText.length() > 80 ? userText.substring(0, 80) + "..." : userText);
            metrics.recordNeedsSearchTrigger();
            KubernetesDynamicSearcher.SearchResult sr = dynamicSearcher.searchAndSave(
                    conversationId, userText,
                    BudgetComputing.dynamicDocCapFor(gpt.budgetInputChars()));
            if (sr != null && sr.context() != null && !sr.context().isBlank()) {
                return new ProactiveFetch(sr.context(), sr.urls());
            }
        } catch (Exception e) {
            logger.warn("Proactive dynamic search failed (continuing without docs): {}", e.getMessage());
        }
        return new ProactiveFetch("", null);
    }

    // -------------------------------------------------------------------------
    // solve() — non-streaming
    // -------------------------------------------------------------------------

    public AiResult solve(String conversationId, String userText, List<Artifact> artifacts) {
        return solve(conversationId, userText, artifacts, true);
    }

    public AiResult solve(String conversationId, String userText, List<Artifact> artifacts,
            boolean recordExchange) {
        final long solveStart = System.currentTimeMillis();
        logger.info("Solve() - non streaming, convId=" + conversationId + " userText" + userText 
                            + " artifactsSize" + (artifacts != null ? artifacts.size() : 0));
        
        // 1. splitBulkPodContext -> Se ia fiecare artefact in parte si se normalizeaza
        ArtifactProcessingRecord APR = solveService.artifactProcessing(userText, artifacts, feedbackRetrievalService, gpt);
        
        // 2. Fetch relevant docs (boost-aware, dynamic ragChars)
        // Aici se construieste un string cu 12 chunkuri cele mai relevante din ES
        // cu cele 2 modalitati de evaluare (BM25 si kNN embedding)
        // Ablation switch: cu RAG dezactivat (config "fara RAG" din evaluare) nu se
        // regaseste nicio documentatie — modelul primeste doar starea clusterului.
        String relevantDocs = ablation.isRagEnabled()
                ? PromptsBuilder.fetchRelevantDocs(userText, APR.processedArtifacts(),
                                                                APR.boosterUrls(),
                                                                APR.budget().ragChars(),
                                                                docsScraper)
                : "";

        // 2b. Proactive dynamic fetch: pragul de relevanta a decis ca baza statica nu
        // acopera intrebarea -> serverul cauta documentatia live INAINTE de primul apel
        // LLM (declansare determinista, nu depinde de emiterea marker-ului de catre model).
        List<String> proactiveUrls = null;
        if (ablation.isDynamicSearchEnabled() && relevantDocs.isBlank()) {
            ProactiveFetch PF = proactiveDynamicFetch(conversationId, userText);
            relevantDocs = PF.docs();
            proactiveUrls = PF.urls();
        }

        // 3. Build current user message content
        // Aici se adauga si mesajul userului si informatia din artefacte
        String userContent = PromptsBuilder.buildUserPrompt(userText, APR.processedArtifacts(),
                APR.budget().perArtifactChars());

        // 3b. Artifact bank management
        ArtifactBankRecord ABR = solveService.artifactBankManagement(conversationId, APR.processedArtifacts());

        // 4. Update history with user message
        solveService.addUserMessageToHistory(conversationId, userContent);

        // 5. Build full message list for Ollama
        FullMessageRecord FMR = solveService.buildFullMessage(conversationId, relevantDocs,
                                                    APR.similarCases(), ABR.bank(), ABR.evictedLabels(),
                                                    APR.budget());

        // Aici se parcurge istoricul si se adauga mesajele din istoric in prompt
        solveService.addHistoryMessageToPrompt(conversationId, FMR.remainingBudget(), FMR.messages(), userContent, FMR.historyEntries());

        // Count assembled prompt chars and flag near-overflow early
        // se verifica sa nu fie overflow la token-uri (sa folosim mai multe tokene decat
        // s-a presupus ca se va folosi)
        final int solvePromptChars = FMR.messages().stream()
                .mapToInt(msg -> msg.getOrDefault("content", "").length())
                .sum();
        metrics.recordNumCtxOverflowIfApplicable(solvePromptChars, gpt.getNumCtx());

        // Se apeleaza gpt
        String assistantText = solveService.callChat(conversationId, FMR.messages(), userText, APR.processedArtifacts(), relevantDocs);

        // --- SECONDARY DYNAMIC RAG LOOP ---
        // Ramane activ ca mecanism secundar: daca modelul totusi emite [NEEDS_SEARCH:]
        // (de ex. considera documentatia primita irelevanta), bucla functioneaza ca inainte.
        var DRR = needsSearchLoopService.dynamicRagLoopFunction(conversationId, assistantText, FMR.messages());

        // 6. Save assistant response to history (sursele: bucla secundara are prioritate,
        // altfel cele din cautarea proactiva)
        List<String> sourceUrls = DRR.dynamicSourceUrls() != null ? DRR.dynamicSourceUrls() : proactiveUrls;
        solveService.saveAssistantResponseToHistory(conversationId, DRR.assistantText(), recordExchange,
                                        sourceUrls, userText, solveStart, solvePromptChars);

        // Sursele merg si in raspunsul API (source_urls), ca sa fie trasabile per cerere
        // (folosit de evaluare pentru a compara citarile modelului cu paginile aduse efectiv).
        return new AiResult(DRR.assistantText(), null, sourceUrls);
    }

    // -------------------------------------------------------------------------
    // Lightweight title generation (ephemeral — no RAG, CBR, history, metrics)
    // -------------------------------------------------------------------------

    public AiResult generateTitle(String userText) {
        logger.info("generateTitle() - lightweight ephemeral path");
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", userText == null ? "" : userText));
        try {
            String raw = gpt.chat(messages);
            if (raw == null || raw.isBlank()) return new AiResult("", null);
            return new AiResult(raw.trim(), null);
        } catch (Exception e) {
            logger.warn("generateTitle: Ollama call failed: {}", e.getMessage());
            return new AiResult("", null);
        }
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
     * <p>{@link #wrapWithDynamicSearchLoop} continuously scans every token for a
     * {@code [NEEDS_SEARCH: query]} marker -- not just an initial window -- so a marker
     * emitted after a long partial answer is still caught. Text that is provably not part
     * of a marker is flushed to the client as soon as it arrives; only an in-progress
     * marker (and the small tail needed to detect one split across chunks) is ever held
     * back, so the marker text itself never reaches the client or the persisted history.
     * Once detected, the original stream is cancelled, {@link KubernetesDynamicSearcher#searchAndSave}
     * runs on a bounded-elastic thread, and a fresh Ollama stream with the augmented prompt
     * is substituted transparently.
     */
    public Flux<StreamChunk> solveStream(String conversationId, String userText, List<Artifact> artifacts) {
        return solveStream(conversationId, userText, artifacts, true);
    }

    public Flux<StreamChunk> solveStream(String conversationId, String userText, List<Artifact> artifacts,
            boolean recordExchange) {
        final long streamSolveStart = System.currentTimeMillis();
        logger.info("Solve() - streaming, convId=" + conversationId + " userText" + userText 
                            + " artifactsSize" + (artifacts != null ? artifacts.size() : 0));
        
        // 1. splitBulkPodContext -> Se ia fiecare artefact in parte si se normalizeaza
        ArtifactProcessingRecord APR = solveService.artifactProcessing(userText, artifacts, feedbackRetrievalService, gpt);
        
        // 2. Fetch relevant docs (boost-aware, dynamic ragChars)
        // Aici se construieste un string cu 12 chunkuri cele mai relevante din ES
        // cu cele 2 modalitati de evaluare (BM25 si kNN embedding)
        // Ablation switch: cu RAG dezactivat (config "fara RAG" din evaluare) nu se
        // regaseste nicio documentatie — modelul primeste doar starea clusterului.
        String relevantDocs = ablation.isRagEnabled()
                ? PromptsBuilder.fetchRelevantDocs(userText, APR.processedArtifacts(),
                                                                APR.boosterUrls(),
                                                                APR.budget().ragChars(),
                                                                docsScraper)
                : "";

        // 2b. Proactive dynamic fetch (vezi solve()). Ruleaza sincron in faza de setup a
        // stream-ului. Nota: pe calea de streaming URL-urile sursei proactive nu sunt
        // propagate in qa_feedback (limitare acceptata); pagina descarcata este oricum
        // salvata si indexata de searchAndSave, deci cunostintele raman in baza.
        if (ablation.isDynamicSearchEnabled() && relevantDocs.isBlank()) {
            relevantDocs = proactiveDynamicFetch(conversationId, userText).docs();
        }

        // 3. Build current user message content
        // Aici se adauga si mesajul userului si informatia din artefacte
        String userContent = PromptsBuilder.buildUserPrompt(userText, APR.processedArtifacts(),
                APR.budget().perArtifactChars());

        // 3b. Artifact bank management
        ArtifactBankRecord ABR = solveService.artifactBankManagement(conversationId, APR.processedArtifacts());

        // 4. Update history with user message
        solveService.addUserMessageToHistory(conversationId, userContent);

        // 5. Build full message list for Ollama
        FullMessageRecord FMR = solveService.buildFullMessage(conversationId, relevantDocs,
                                                    APR.similarCases(), ABR.bank(), ABR.evictedLabels(),
                                                    APR.budget());

        // Aici se parcurge istoricul si se adauga mesajele din istoric in prompt
        solveService.addHistoryMessageToPrompt(conversationId, FMR.remainingBudget(), FMR.messages(), userContent, FMR.historyEntries());

        // Count assembled prompt chars and flag near-overflow early
        // se verifica sa nu fie overflow la token-uri (sa folosim mai multe tokene decat
        // s-a presupus ca se va folosi)
        final int solvePromptChars = FMR.messages().stream()
                .mapToInt(msg -> msg.getOrDefault("content", "").length())
                .sum();
        metrics.recordNumCtxOverflowIfApplicable(solvePromptChars, gpt.getNumCtx());

        // Final function call for streaming
        return solveService.streamFluxFunction(conversationId, userText,
                                                APR.processedArtifacts(), FMR.messages(),
                                                relevantDocs, streamSolveStart, recordExchange);
    }

    // -------------------------------------------------------------------------
    // Fallback answer (when Ollama is unavailable)
    // -------------------------------------------------------------------------

    public static String fallbackAnswer(String userText, List<Artifact> artifacts, String relevantDocs) {
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
    // Public result types
    // -------------------------------------------------------------------------

    public static class AiResult {
        private final String assistantText;
        private final List<AiAction> actions;
        /** URL-urile paginilor aduse de căutarea dinamică (proactivă sau prin marker); null dacă nu a căutat. */
        private final List<String> sourceUrls;

        public AiResult(String assistantText, List<AiAction> actions) {
            this(assistantText, actions, null);
        }

        public AiResult(String assistantText, List<AiAction> actions, List<String> sourceUrls) {
            this.assistantText = assistantText;
            this.actions = actions;
            this.sourceUrls = sourceUrls;
        }

        public String getAssistantText() {
            return assistantText;
        }

        public List<AiAction> getActions() {
            return actions;
        }

        public List<String> getSourceUrls() {
            return sourceUrls;
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
