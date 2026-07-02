package com.kdiag.server.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kdiag.server.ai.helpers.BudgetComputing;
import com.kdiag.server.config.AblationConfig;
import com.kdiag.server.llm.GptChatClient;

/**
 * Runtime tuning endpoints for benchmarking the context window without a restart.
 *
 * <p>Switching {@code num_ctx} (e.g. 16384 vs 32768) live lets you measure latency at each
 * setting and compare. Every prompt budget (total envelope, artifacts, RAG) is derived from
 * the current {@code num_ctx}, so a single switch rescales all of them together.
 *
 * <p>Typical benchmark loop:
 * <ol>
 *   <li>{@code POST /v1/metrics/reset}</li>
 *   <li>{@code POST /v1/config/num-ctx?value=16384}</li>
 *   <li>send a few chat requests</li>
 *   <li>{@code GET /v1/metrics} → read avgResponseTimeMs, lastPromptTokens, etc.</li>
 *   <li>repeat with {@code value=32768} and compare</li>
 * </ol>
 */
@RestController
@RequestMapping(path = "/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
public class RuntimeConfigController {

    private final GptChatClient gpt;
    private final AblationConfig ablation;

    public RuntimeConfigController(GptChatClient gpt, AblationConfig ablation) {
        this.gpt = gpt;
        this.ablation = ablation;
    }

    /**
     * Returns the current RAG ablation mode ({@code none | static | dynamic})
     * together with the two underlying flags.
     */
    @GetMapping("/rag-mode")
    public Map<String, Object> ragMode() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", ablation.mode());
        m.put("ragEnabled", ablation.isRagEnabled());
        m.put("dynamicSearchEnabled", ablation.isDynamicSearchEnabled());
        return m;
    }

    /**
     * Switches the RAG ablation mode at runtime (no restart), mirroring the
     * {@code num_ctx} benchmarking flow. Accepted values: {@code none}, {@code static},
     * {@code dynamic}.
     */
    @PostMapping("/rag-mode")
    public Map<String, Object> setRagMode(@RequestParam String value) {
        ablation.setMode(value);
        return ragMode();
    }

    /** Switches num_ctx at runtime and returns the recomputed budget. */
    @PostMapping("/num-ctx")
    public Map<String, Object> setNumCtx(@RequestParam int value) {
        gpt.setNumCtx(value);
        return budget();
    }

    /** Returns the current context window and every budget derived from it. */
    @GetMapping("/budget")
    public Map<String, Object> budget() {
        int numCtx     = gpt.getNumCtx();
        int inputChars = gpt.budgetInputChars();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("numCtx", numCtx);
        m.put("outputReserveFraction", gpt.getOutputReserveFraction());
        m.put("outputTokenReserve", gpt.getOutputTokenReserve());
        m.put("charsPerToken", gpt.getCharsPerToken());
        m.put("inputCharCapacity", inputChars);
        m.put("maxTotalPromptChars", inputChars);
        m.put("maxTotalArtifactChars", BudgetComputing.artifactCapFor(inputChars));
        m.put("ragMinChars", BudgetComputing.ragMinFor(inputChars));
        m.put("ragMaxChars", BudgetComputing.ragMaxFor(inputChars));
        m.put("bankBudgetChars", BudgetComputing.bankBudgetFor(inputChars));
        m.put("dynamicDocCapChars", BudgetComputing.dynamicDocCapFor(inputChars));
        return m;
    }
}
