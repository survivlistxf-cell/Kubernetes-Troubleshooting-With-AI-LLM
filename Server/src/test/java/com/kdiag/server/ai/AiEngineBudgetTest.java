package com.kdiag.server.ai;

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

    @BeforeEach
    void setUp() {
        // computeArtifactBudget uses no injected fields — null deps are safe here
        engine = new AiEngine(null, null, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Test 1: empty list
    // -----------------------------------------------------------------------

    @Test
    void emptyList_returnsZeroArtifactsAndMaxRag() {
        AiEngine.ArtifactBudget budget = engine.computeArtifactBudget(List.of());
        assertArrayEquals(new int[0], budget.perArtifactChars());
        assertEquals(0, budget.totalArtifactChars());
        assertEquals(14000, budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 2: single small artifact (2000 chars)
    // -----------------------------------------------------------------------

    @Test
    void singleSmallArtifact_fullAllocationAndReducedRag() {
        AiEngine.ArtifactBudget budget = engine.computeArtifactBudget(List.of(artifact(2000)));
        assertArrayEquals(new int[]{2000}, budget.perArtifactChars());
        assertEquals(2000, budget.totalArtifactChars());
        // ragChars = max(6000, 14000 - round(2000 * 0.5)) = max(6000, 13000) = 13000
        assertEquals(13000, budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 3: single huge artifact (30000 chars) — capped at 15000
    // -----------------------------------------------------------------------

    @Test
    void singleHugeArtifact_cappedAt15000AndMinRag() {
        AiEngine.ArtifactBudget budget = engine.computeArtifactBudget(List.of(artifact(30000)));
        assertArrayEquals(new int[]{15000}, budget.perArtifactChars());
        assertEquals(15000, budget.totalArtifactChars());
        // ragChars = max(6000, 14000 - round(15000 * 0.5)) = max(6000, 14000-7500) = max(6000,6500) = 6500
        assertEquals(6500, budget.ragChars());
    }

    // -----------------------------------------------------------------------
    // Test 4: FIFO truncation across three artifacts [4000, 5000, 11000]
    // -----------------------------------------------------------------------

    @Test
    void threeArtifacts_fifoTruncation() {
        List<Artifact> arts = List.of(artifact(4000), artifact(5000), artifact(11000));
        AiEngine.ArtifactBudget budget = engine.computeArtifactBudget(arts);
        // Allocation: 4000 (used=4000), 5000 (used=9000), min(11000, 15000-9000)=6000 (used=15000)
        assertArrayEquals(new int[]{4000, 5000, 6000}, budget.perArtifactChars());
        assertEquals(15000, budget.totalArtifactChars());
        // ragChars = max(6000, 14000 - round(15000 * 0.5)) = max(6000, 6500) = 6500
        assertEquals(6500, budget.ragChars());
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
            AiEngine.ArtifactBudget budget = engine.computeArtifactBudget(arts);
            assertTrue(budget.totalArtifactChars() <= 15000,
                    "totalArtifactChars must be <= 15000, was " + budget.totalArtifactChars());
            assertTrue(budget.ragChars() >= 6000,
                    "ragChars must be >= 6000, was " + budget.ragChars());
            assertTrue(budget.ragChars() <= 14000,
                    "ragChars must be <= 14000, was " + budget.ragChars());
            assertTrue(budget.totalArtifactChars() + budget.ragChars() <= 21500,
                    "totalArtifactChars + ragChars must be <= 21500, was "
                            + (budget.totalArtifactChars() + budget.ragChars()));
        }
    }

    // -----------------------------------------------------------------------
    // Test 6: HistoryService FIFO eviction keeps last 5
    // -----------------------------------------------------------------------

    @Test
    void historyService_fifoEviction_keepsLast5() {
        HistoryService hs = new HistoryService();
        String convId = "test-conv";
        for (int i = 0; i < 7; i++) {
            hs.addArtifacts(convId, List.of(artifact(100, "content-" + i)));
        }
        List<HistoryService.BankedArtifact> bank = hs.getBankedArtifacts(convId);
        assertEquals(5, bank.size(), "Bank should hold exactly 5 entries after 7 additions");
        // Oldest surviving turn is 3 (turns 1 and 2 were evicted)
        for (int i = 0; i < 5; i++) {
            assertEquals(3 + i, bank.get(i).turnNumber(),
                    "Expected turn " + (3 + i) + " at position " + i);
        }
    }

    // -----------------------------------------------------------------------
    // Test 7: getBankedArtifactsBefore filters by turn correctly
    // -----------------------------------------------------------------------

    @Test
    void historyService_getBankedArtifactsBefore_filtersCorrectly() {
        HistoryService hs = new HistoryService();
        String convId = "test-conv";
        long t1 = hs.addArtifacts(convId, List.of(artifact(50, "alpha")));
        long t2 = hs.addArtifacts(convId, List.of(artifact(50, "beta")));
        long t3 = hs.addArtifacts(convId, List.of(artifact(50, "gamma")));

        List<HistoryService.BankedArtifact> beforeT3 = hs.getBankedArtifactsBefore(convId, t3);
        assertEquals(2, beforeT3.size(), "Should return entries from t1 and t2 only");
        assertEquals(t1, beforeT3.get(0).turnNumber());
        assertEquals(t2, beforeT3.get(1).turnNumber());

        // Asking before t1 should return nothing
        List<HistoryService.BankedArtifact> beforeT1 = hs.getBankedArtifactsBefore(convId, t1);
        assertTrue(beforeT1.isEmpty(), "Nothing should precede t1");
    }

    // -----------------------------------------------------------------------
    // Test 8: summary truncated at 1500 chars with correct suffix
    // -----------------------------------------------------------------------

    @Test
    void bankedArtifact_summaryCappedAt1500WithTruncationSuffix() {
        HistoryService hs = new HistoryService();
        String convId = "test-conv";
        String longContent = "A".repeat(3000);
        long turn = hs.addArtifacts(convId, List.of(artifact(3000, longContent)));

        List<HistoryService.BankedArtifact> bank = hs.getBankedArtifacts(convId);
        assertEquals(1, bank.size());
        String summary = bank.get(0).summary();
        assertTrue(summary.startsWith("A".repeat(1500)),
                "Summary should start with first 1500 chars of content");
        assertTrue(summary.contains("...[truncated, full version was in turn " + turn + "]"),
                "Summary should contain truncation notice with correct turn number");
    }

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
