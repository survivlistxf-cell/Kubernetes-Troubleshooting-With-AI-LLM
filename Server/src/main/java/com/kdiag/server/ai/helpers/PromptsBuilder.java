package com.kdiag.server.ai.helpers;

import java.util.Locale;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.docs.KubernetesDocsScraper;
import com.kdiag.server.protocol.KdiagModels.Artifact;

public class PromptsBuilder {

    private static final int MAX_RETRIEVAL_SNIPPET_CHARS = 400;

    private static final Logger logger = LoggerFactory.getLogger(BudgetComputing.class);

    private static final String NEEDS_SEARCH_CONTRACT =
        "DYNAMIC SEARCH: If the provided documentation is insufficient to answer accurately," +
        " do NOT guess and do NOT cite anything. Instead, START your reply with the marker on its own line:\n" +
        "[NEEDS_SEARCH: <short exact query for kubernetes.io>]\n" +
        "Example: [NEEDS_SEARCH: nginx ingress 403 error]\n" +
        "I will fetch the docs and let you continue. Use this whenever the user explicitly asks you to" +
        " search, or when the documentation block below is empty or irrelevant.\n";
    // Minimum useful slice of an artifact before we degrade to a filename-only breadcrumb.
    private static final int BANK_SUMMARY_MIN_CHARS = 600;

    // Marker appended to the user message when the artifact budget could not fit all attached
    // evidence (an artifact was cut to its allocation, or dropped entirely). Signals to the LLM
    // that the evidence below may be incomplete.
    private static final String TRUNCATION_TAG =
        "\n--- TRUNCATED BECAUSE OF LIMITS ---\n";

    // System-prompt note explaining how the LLM should treat the marker above. Always included
    // (phrased conditionally) so the model knows what the tag means when it appears.
    private static final String TRUNCATION_NOTICE =
        "EVIDENCE LIMITS: If you see the marker '--- TRUNCATED BECAUSE OF LIMITS ---' in the user " +
        "message, the attached evidence (logs/describe/events) was shortened to fit size limits and " +
        "may be incomplete. Do NOT assume a section is empty or a problem is absent just because a " +
        "detail is missing; if a decisive detail seems to be missing, say so and ask the user to " +
        "re-attach or narrow to the specific pod/resource rather than guessing.\n";

    /**
     * Builds the system prompt.
     * isFirstTurn=true → full verbose preamble.
     * isFirstTurn=false → compact ~200-char preamble; dynamic sections always emitted in full.
     */
    public static String buildSystemPrompt(String relevantDocs, String conversationSummary,
                                     List<FeedbackRetrievalService.SimilarCase> similarCases,
                                     List<HistoryService.BankedTurn> bank,
                                     List<String> evictedLabels,
                                     BudgetComputing.ArtifactBudget budget,
                                     boolean isFirstTurn,
                                     int  budgetInputChars) {
        StringBuilder sb = new StringBuilder();

        if (isFirstTurn) {
            sb.append("You are a Kubernetes diagnostics assistant.\n");
            sb.append("Be direct and helpful.\n\n");
            sb.append("IMPORTANT ACTIONS:\n");
            sb.append(
                    "- If user asks to 'stop commands', 'no commands', or 'without commands': analyze only, suggest NO kubectl commands\n");
            sb.append("- If user provides error messages or logs: explain what they mean\n");
            sb.append("- Focus on understanding the problem first, not just solutions\n");
            sb.append(NEEDS_SEARCH_CONTRACT);
            sb.append(TRUNCATION_NOTICE);
            sb.append("\n");
        } else {
            sb.append("You are Kubexplain, the Kubernetes diagnostic assistant. Continue this conversation " +
                "applying the same conventions established earlier: structure responses in readable markdown.\n\n");
            sb.append(NEEDS_SEARCH_CONTRACT);
            sb.append(TRUNCATION_NOTICE);
            sb.append("\n");
        }

        if (conversationSummary != null && !conversationSummary.isBlank()) {
            sb.append("Conversation summary so far:\n");
            sb.append(conversationSummary.trim());
            sb.append("\n\n");
        }

        // Case-based retrieval hits — positively-rated past Q&A pairs
        if (similarCases != null && !similarCases.isEmpty()) {
            StringBuilder casesBlock = new StringBuilder(
                    "=== PREVIOUSLY SUCCESSFUL ANSWERS (use as guidance) ===\n");
            int casesBudget = 4000;
            for (FeedbackRetrievalService.SimilarCase c : similarCases) {
                if (casesBudget <= 0) break;
                StringBuilder entry = new StringBuilder(String.format(Locale.ROOT,
                        "User asked: %s\nAnswer: %s\nSimilarity: %.2f\n",
                        c.userQuestion(), c.aiResponse(), c.similarity()));
                if (c.sourceUrls() != null && !c.sourceUrls().isEmpty()) {
                    entry.append("Sources: ")
                            .append(String.join(", ", c.sourceUrls()))
                            .append('\n');
                }
                entry.append("---\n");
                String entryText = entry.toString();
                if (entryText.length() > casesBudget) {
                    entryText = entryText.substring(0, casesBudget);
                }
                casesBlock.append(entryText);
                casesBudget -= entryText.length();
            }
            casesBlock.append("==========================================\n\n");
            sb.append(casesBlock);
        }

        // Historical artifact bank (prior turns only — current turn is in the user message).
        // Rendered within a dynamic budget: full content while it fits, then summary, then a
        // filename-only breadcrumb so the LLM knows the artifact existed but its content is gone.
        sb.append(renderBank(bank, evictedLabels, BudgetComputing.effectiveBankBudget(budget, budgetInputChars)));

        if (relevantDocs != null && !relevantDocs.isBlank()) {
            sb.append("Official Kubernetes docs (from cache):\n");
            sb.append(relevantDocs);
            sb.append("\n");
        }

        boolean hasAnyContext = (relevantDocs != null && !relevantDocs.isBlank())
                || (similarCases != null && !similarCases.isEmpty())
                || (bank != null && !bank.isEmpty());
        if (hasAnyContext) {
            sb.append("Cite ONLY URLs that appear verbatim in the context above. Never invent or guess links.\n");
        } else {
            sb.append("No documentation is available. Do NOT include any links or a 'References'/'Sources' section. " +
                "Either answer from general knowledge clearly labeled as such, or emit a [NEEDS_SEARCH: ...] marker as described above.\n");
        }

        return sb.toString();
    }

    /**
     * Renders the artifact bank within {@code budgetChars}, newest-first: the freshest prior
     * artifacts keep full content (the artifacts of the turn the user is asking in are already in
     * the current user message, so this bank is prior turns only). Once budget runs out the rest
     * degrade: full → summary (head slice) → breadcrumb ("[content not available — ask to
     * re-attach]"). Evicted turns are listed as breadcrumbs too. Returns "" when there is nothing
     * to show. Static for unit testing.
     */
    static String renderBank(List<HistoryService.BankedTurn> turns,
                             List<String> evictedLabels, int budgetChars) {
        boolean hasTurns   = turns != null && !turns.isEmpty();
        boolean hasEvicted = evictedLabels != null && !evictedLabels.isEmpty();
        if (!hasTurns && !hasEvicted) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Reference artifacts attached earlier in this conversation\n");
        sb.append("(Uploaded by the user in previous turns. Use them as context only if relevant. "
                + "If an artifact you need is missing below or marked [content not available] or "
                + "[truncated], ask the user to re-attach it — do not invent its contents.)\n\n");

        if (hasTurns) {
            List<HistoryService.BankedTurn> ordered = new java.util.ArrayList<>(turns);
            ordered.sort((a, b) -> Long.compare(b.turnNumber(), a.turnNumber())); // newest first
            int remaining = budgetChars;
            for (HistoryService.BankedTurn t : ordered) {
                String header = "[turn " + t.turnNumber() + " — " + t.label() + "]\n";
                String content = t.content() == null ? "" : t.content();
                String body;
                if (remaining >= header.length() + content.length() + 2) {
                    body = content;                                   // full
                } else if (remaining >= header.length() + BANK_SUMMARY_MIN_CHARS) {
                    int take = remaining - header.length() - 60;      // leave room for the suffix
                    body = content.substring(0, Math.max(0, Math.min(take, content.length())))
                            + "\n...[truncated — ask the user to re-attach for the full version]";
                } else {
                    body = "[content not available — ask the user to re-attach if needed]";
                }
                String entry = header + body + "\n\n";
                sb.append(entry);
                remaining -= entry.length();
            }
        }

        if (hasEvicted) {
            sb.append("Older attachments no longer in context (ask the user to re-attach if needed): ");
            sb.append(String.join("; ", evictedLabels)).append("\n\n");
        }
        return sb.toString();
    }

    /* ***** USER PROMPT ***** */
    public static String buildUserPrompt(String userText, List<Artifact> artifacts,
                                   int[] perArtifactChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(userText == null ? "" : userText);
        sb.append("\n");

        if (artifacts != null && !artifacts.isEmpty()) {
            StringBuilder artifactSection = new StringBuilder();
            boolean truncated = false;
            for (int i = 0; i < artifacts.size(); i++) {
                Artifact a = artifacts.get(i);
                if (a == null) continue;
                int alloc = (i < perArtifactChars.length) ? perArtifactChars[i] : 0;
                String content = a.getContent() == null ? "" : a.getContent();
                if (alloc <= 0) {
                    // artifact fully dropped because the artifact budget was exhausted
                    if (!content.isBlank()) truncated = true;
                    continue;
                }
                if (content.length() > alloc) truncated = true; // artifact cut to fit the budget
                String label = a.getType();
                if (a.getTarget() != null && !a.getTarget().isBlank()) {
                    label = label + " — " + a.getTarget(); // e.g. "pod_logs — kubexplain/ai-server-..."
                }
                artifactSection.append("[").append(label).append("]\n");
                artifactSection.append(BudgetComputing.truncate(a.getContent(), alloc)).append("\n\n");
            }
            if (artifactSection.length() > 0) {
                sb.append("\n--- New evidence provided in this turn ---\n");
                sb.append(artifactSection);
            }
            // Global signal: at least one artifact was cut or dropped by the artifact budget.
            if (truncated) {
                sb.append(TRUNCATION_TAG);
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // RAG builder
    // -------------------------------------------------------------------------

    public static String fetchRelevantDocs(String userText, List<Artifact> artifacts,
                                     Set<String> boostedUrls, int maxRagChars,
                                     KubernetesDocsScraper docsScraper) {
        try {
            StringBuilder query = new StringBuilder(userText == null ? "" : userText);
            if (artifacts != null) {
                for (Artifact a : artifacts) {
                    if (a != null && a.getType() != null) {
                        query.append(" ").append(a.getType());
                    }
                    if (a != null && a.getContent() != null) {
                        String snippet = a.getContent().length() > MAX_RETRIEVAL_SNIPPET_CHARS
                            ? a.getContent().substring(0, MAX_RETRIEVAL_SNIPPET_CHARS)
                                : a.getContent();
                        query.append(" ").append(snippet);
                    }
                }
            }

            String hybridSearchResult = docsScraper.getRelevantDocsHybridBoosted(
                    query.toString(), maxRagChars, boostedUrls);
            if (!hybridSearchResult.isBlank()) {
                return hybridSearchResult;
            }
            return docsScraper.getRelevantDocs(query.toString());
        } catch (Exception e) {
            logger.error("Failed to fetch docs (continuing without docs)", e);
            return "";
        }
    }
}
