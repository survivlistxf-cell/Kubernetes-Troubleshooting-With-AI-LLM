package com.kdiag.server.ai;

import com.kdiag.server.ai.helpers.BudgetComputing;
import com.kdiag.server.ai.helpers.PromptsBuilder;
import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.protocol.KdiagModels.Artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AiEngineBudgetTest {

    private AiEngine engine;

    // Arbitrary fixed input capacity for deterministic budget tests (independent of num_ctx /
    // output-reserve config). Budgets are derived from it via the same fraction helpers the
    // production code uses, so the assertions track the policy instead of hardcoded magic numbers.
    private static final int CAP     = 92160;
    private static final int MAX_ART = BudgetComputing.artifactCapFor(CAP); // 41472
    private static final int MAX_RAG = BudgetComputing.ragMaxFor(CAP);      // 30412
    private static final int MIN_RAG = BudgetComputing.ragMinFor(CAP);      // 11980
    private static final double RATIO = 0.5;

    private static int expectedRag(int used) {
        return Math.max(MIN_RAG, MAX_RAG - (int) Math.round(used * RATIO));
    }

    @BeforeEach
    void setUp() {
        // computeArtifactBudget(artifacts, inputChars) uses no injected fields — null deps are safe
        engine = new AiEngine(null, null, null, null, null, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Test 1: empty list
    // -----------------------------------------------------------------------

    @Test
    void emptyList_returnsZeroArtifactsAndMaxRag() {
        BudgetComputing.ArtifactBudget budget = BudgetComputing.computeArtifactBudget(List.of(), CAP);
        assertArrayEquals(new int[0], budget.perArtifactChars());
        assertEquals(0, budget.totalArtifactChars());
        assertEquals(MAX_RAG, budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 2: single small artifact (2000 chars)
    // -----------------------------------------------------------------------

    @Test
    void singleSmallArtifact_fullAllocationAndReducedRag() {
        BudgetComputing.ArtifactBudget budget = BudgetComputing.computeArtifactBudget(List.of(artifact(2000)), CAP);
        assertArrayEquals(new int[]{2000}, budget.perArtifactChars());
        assertEquals(2000, budget.totalArtifactChars());
        assertEquals(expectedRag(2000), budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 3: single huge artifact — capped at MAX_ART
    // -----------------------------------------------------------------------

    @Test
    void singleHugeArtifact_cappedAndMinRag() {
        BudgetComputing.ArtifactBudget budget = BudgetComputing.computeArtifactBudget(List.of(artifact(200000)), CAP);
        assertArrayEquals(new int[]{MAX_ART}, budget.perArtifactChars());
        assertEquals(MAX_ART, budget.totalArtifactChars());
        assertEquals(expectedRag(MAX_ART), budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 4: FIFO truncation across three artifacts
    // -----------------------------------------------------------------------

    @Test
    void threeArtifacts_fifoTruncation() {
        List<Artifact> arts = List.of(artifact(20000), artifact(20000), artifact(20000));
        BudgetComputing.ArtifactBudget budget = BudgetComputing.computeArtifactBudget(arts, CAP);
        // 20000 (used=20000), 20000 (used=40000), min(20000, MAX_ART-40000) for the third
        int third = MAX_ART - 40000;
        assertArrayEquals(new int[]{20000, 20000, third}, budget.perArtifactChars());
        assertEquals(MAX_ART, budget.totalArtifactChars());
        assertEquals(expectedRag(MAX_ART), budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 5: invariants hold for random inputs
    // -----------------------------------------------------------------------

    @Test
    void randomInputs_invariantsAlwaysHold() {
        Random rng = new Random(42);
        for (int trial = 0; trial < 50; trial++) {
            int n = 1 + rng.nextInt(20);
            List<Artifact> arts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                arts.add(artifact(rng.nextInt(50001)));
            }
            BudgetComputing.ArtifactBudget budget = BudgetComputing.computeArtifactBudget(arts, CAP);
            assertTrue(budget.totalArtifactChars() <= MAX_ART,
                    "totalArtifactChars must be <= " + MAX_ART + ", was " + budget.totalArtifactChars());
            assertTrue(budget.ragChars() >= MIN_RAG,
                    "ragChars must be >= " + MIN_RAG + ", was " + budget.ragChars());
            assertTrue(budget.ragChars() <= MAX_RAG,
                    "ragChars must be <= " + MAX_RAG + ", was " + budget.ragChars());
            assertTrue(budget.totalArtifactChars() + budget.ragChars() <= MAX_ART + MAX_RAG,
                    "totalArtifactChars + ragChars must be <= " + (MAX_ART + MAX_RAG) + ", was "
                            + (budget.totalArtifactChars() + budget.ragChars()));
        }
    }

    // -----------------------------------------------------------------------
    // Test 6: HistoryService FIFO eviction keeps last 15 turns + breadcrumbs for evicted
    // -----------------------------------------------------------------------

    @Test
    void historyService_fifoEviction_keepsLast15Turns_andBreadcrumbsEvicted() {
        HistoryService hs = new HistoryService();
        String convId = "test-conv";
        for (int i = 0; i < 17; i++) {
            hs.addArtifacts(convId, List.of(artifact(100, "content-" + i)));
        }
        List<HistoryService.BankedTurn> bank = hs.getBankedTurns(convId);
        assertEquals(15, bank.size(), "Bank should hold exactly 15 turns after 17 additions");
        // Oldest surviving turn is 3 (turns 1 and 2 were evicted)
        for (int i = 0; i < 15; i++) {
            assertEquals(3 + i, bank.get(i).turnNumber(),
                    "Expected turn " + (3 + i) + " at position " + i);
        }
        // Evicted turns 1 and 2 kept as breadcrumbs
        List<String> evicted = hs.getEvictedTurnLabels(convId);
        assertEquals(2, evicted.size());
        assertTrue(evicted.get(0).startsWith("turn 1:"), "First breadcrumb should be turn 1");
    }

    // -----------------------------------------------------------------------
    // Test 7: getBankedTurnsBefore filters by turn correctly
    // -----------------------------------------------------------------------

    @Test
    void historyService_getBankedTurnsBefore_filtersCorrectly() {
        HistoryService hs = new HistoryService();
        String convId = "test-conv";
        long t1 = hs.addArtifacts(convId, List.of(artifact(50, "alpha")));
        long t2 = hs.addArtifacts(convId, List.of(artifact(50, "beta")));
        long t3 = hs.addArtifacts(convId, List.of(artifact(50, "gamma")));

        List<HistoryService.BankedTurn> beforeT3 = hs.getBankedTurnsBefore(convId, t3);
        assertEquals(2, beforeT3.size(), "Should return turns t1 and t2 only");
        assertEquals(t1, beforeT3.get(0).turnNumber());
        assertEquals(t2, beforeT3.get(1).turnNumber());

        // Asking before t1 should return nothing
        assertTrue(hs.getBankedTurnsBefore(convId, t1).isEmpty(), "Nothing should precede t1");
    }

    // -----------------------------------------------------------------------
    // Test 8: full content stored; renderBank keeps it full on big budget, degrades on tiny
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Test 9: renderBank keeps the NEWEST turn full when budget is tight (recency)
    // -----------------------------------------------------------------------


    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Artifact artifact(int contentLength) {
        Artifact a = new Artifact();
        a.setType("test");
        a.setContent("x".repeat(contentLength));
        return a;
    }

    private static Artifact artifact(int ignored, String content) {
        Artifact a = new Artifact();
        a.setType("test");
        a.setContent(content);
        return a;
    }
}
