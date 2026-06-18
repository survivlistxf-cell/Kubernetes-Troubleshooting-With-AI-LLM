package com.kdiag.server.ai.helpers;

import java.util.List;
import java.util.Map;

import com.kdiag.server.protocol.KdiagModels.Artifact;

public class BudgetComputing {

    // Size-based artifact budget — expressed as FRACTIONS of the live input char capacity
    // (ollama.budgetInputChars(), itself derived from num_ctx). Switching num_ctx at runtime
    // (16384 <-> 32768) therefore rescales the prompt envelope, artifacts and RAG together.
    private static final double ARTIFACT_BUDGET_FRACTION = 0.45; // artifacts use up to 45% of input
    private static final double RAG_MAX_FRACTION         = 0.33; // RAG ceiling when no artifacts
    private static final double RAG_MIN_FRACTION         = 0.13; // RAG floor under artifact pressure
    private static final double ARTIFACT_TO_RAG_RATIO    = 0.5;  // 1 artifact char "costs" 0.5 RAG char

    private static final double BANK_FRACTION = 0.12; // baseline bank share of input
    // Reserve kept free for the fixed system sections (preamble + summary + similar cases) plus a
    // little history headroom, so the bank borrowing leftover artifact budget can't starve them.
    private static final int    BANK_SYSTEM_OVERHEAD = 7000;

    public static int artifactCapFor(int inputChars) { return (int) (inputChars * ARTIFACT_BUDGET_FRACTION); }
    public static int ragMaxFor(int inputChars)      { return (int) (inputChars * RAG_MAX_FRACTION); }
    public static int ragMinFor(int inputChars)      { return (int) (inputChars * RAG_MIN_FRACTION); }
    public static int bankBudgetFor(int inputChars)  { return (int) (inputChars * BANK_FRACTION); }

    // How much web documentation a [NEEDS_SEARCH:] round may pull, scaled with the context window.
    private static final double DYNAMIC_DOC_FRACTION = 0.20;
    public static int dynamicDocCapFor(int inputChars) { return (int) (inputChars * DYNAMIC_DOC_FRACTION); }
    

    // -------------------------------------------------------------------------
    // Artifact budget
    // -------------------------------------------------------------------------

    /** Per-request artifact allocation computed by {@link #computeArtifactBudget}. */
    public record ArtifactBudget(int[] perArtifactChars, int totalArtifactChars, int ragChars) {}

    /**
     * FIFO size-based allocation: each artifact gets min(rawLen, remaining capacity).
     * Total artifact chars are capped at artifactCapFor(inputChars).
     * RAG chars are reduced by ARTIFACT_TO_RAG_RATIO per artifact char consumed, floored at
     * ragMinFor(inputChars). Pure function of (artifacts, inputChars) — no injected fields, so
     * it is unit-testable with a null-constructed AiEngine.
     */
    public static ArtifactBudget computeArtifactBudget(List<Artifact> artifacts, int inputChars) {
        int maxArtifact = artifactCapFor(inputChars);   //calculeaza maximul de caractere 
                                                        //ce pot fi folosite pentru artefacte
                                                        //pentru num_ctx-ul folosit
        int maxRag      = ragMaxFor(inputChars);        // maximul pt rag
        int minRag      = ragMinFor(inputChars);        // minimul pt rag
        if (artifacts == null || artifacts.isEmpty()) {
            return new ArtifactBudget(new int[0], 0, maxRag);
        }
        int[] alloc = new int[artifacts.size()];
        int used = 0;
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            // rawLen este dimensiunea artefactului full, fara trunchiere, dupa normalizare
            int rawLen = (a == null || a.getContent() == null) ? 0 : a.getContent().length();
            // remaining pastreaza bugetul ramas dupa ce folosim din el
            int remaining = maxArtifact - used;
            if (remaining <= 0) { alloc[i] = 0; continue; }
            //mecanismul de fifo, adaugam fie rawLen (tot ce contine artefactul), fie cat ne-a mai ramas
            alloc[i] = Math.min(rawLen, remaining);
            used += alloc[i];
        }
        // procesam cat ne-a mai ramas pentru RAG, pastrand mereu un minim
        int ragChars = Math.max(minRag,
                maxRag - (int) Math.round(used * ARTIFACT_TO_RAG_RATIO));
        return new ArtifactBudget(alloc, used, ragChars);
    }

    /**
     * Effective bank budget = baseline bank share + whatever artifact budget the current turn did
     * NOT consume (so when no new artifacts are attached, prior/foundational artifacts can render
     * in full from the freed artifact budget). Capped so it never starves the current user message.
     */
    public static int effectiveBankBudget(ArtifactBudget budget, int input) {
        int leftover = Math.max(0, artifactCapFor(input) - budget.totalArtifactChars());
        int desired  = bankBudgetFor(input) + leftover;
        int ceiling  = Math.max(bankBudgetFor(input),
                input - budget.ragChars() - budget.totalArtifactChars() - BANK_SYSTEM_OVERHEAD);
        return Math.min(desired, ceiling);
    }

    // -------------------------------------------------------------------------
    // Dynamic-search docs budgeting (shared by blocking + streaming second round)
    // -------------------------------------------------------------------------

    private static final String DYN_DOCS_PREFIX =
            "Here is additional documentation from Kubernetes website based on your search:\n\n";
    private static final String DYN_DOCS_SUFFIX = "\n\nPlease solve the user's issue now.";

    /**
     * Char budget for the dynamic-search docs injected into the SECOND-round prompt. The first
     * round already consumed part of the window, so this is what is left of budgetInputChars()
     * after the messages assembled so far, capped at the RAG proportion (dynamic docs play the
     * RAG role here). Guarantees the second Ollama call stays inside num_ctx.
     */
    public static int dynamicDocBudget(List<Map<String, String>> messagesSoFar, int input) {
        int used = 0;
        for (Map<String, String> m : messagesSoFar) {
            used += m.getOrDefault("content", "").length();
        }
        int margin = DYN_DOCS_PREFIX.length() + DYN_DOCS_SUFFIX.length() + 100;
        int remaining = input - used - margin;
        return Math.max(0, Math.min(BudgetComputing.ragMaxFor(input), remaining));
    }

    /** Wraps the dynamic docs in the standard instruction, clipped to {@code docBudget} chars. */
    public static String dynamicDocsMessage(String dynamicDocsText, int docBudget) {
        return DYN_DOCS_PREFIX + BudgetComputing.truncateToBudget(dynamicDocsText, docBudget) + DYN_DOCS_SUFFIX;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    public static String truncate(String s, int max) {
        if (s == null)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max) + "\n...[truncated (limit 10k)]";
    }

    public static String truncateToBudget(String s, int maxChars) {
        if (s == null)
            return "";
        if (maxChars <= 0)
            return "";
        if (s.length() <= maxChars)
            return s;
        return s.substring(0, maxChars);
    }
}
