package com.kdiag.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime switches for the RAG ablation study (Chapter 6 evaluation).
 *
 * <p>Two independent flags control the retrieval pipeline:
 * <ul>
 *   <li>{@code ragEnabled} — when false, no documentation context is retrieved at all
 *       (neither Lucene nor ElasticSearch); the model answers from the cluster state alone.</li>
 *   <li>{@code dynamicSearchEnabled} — when false, the {@code [NEEDS_SEARCH: ...]} loop is
 *       disabled: the system prompt no longer advertises the marker, and any marker the
 *       model still emits is stripped instead of triggering a live search (air-gapped mode).</li>
 * </ul>
 *
 * <p>The two flags map onto the three evaluated configurations:
 * <pre>
 *   mode "none"    = ragEnabled=false, dynamicSearchEnabled=false   (no RAG)
 *   mode "static"  = ragEnabled=true,  dynamicSearchEnabled=false   (RAG, air-gapped)
 *   mode "dynamic" = ragEnabled=true,  dynamicSearchEnabled=true    (RAG + dynamic search)
 * </pre>
 *
 * <p>Defaults come from {@code kdiag.rag.enabled} / {@code kdiag.search.dynamic.enabled}
 * (env: {@code KDIAG_RAG_ENABLED}, {@code KDIAG_DYNAMIC_SEARCH_ENABLED}) and can be switched
 * live via {@code POST /v1/config/rag-mode} — same pattern as {@code num_ctx} — so the whole
 * ablation matrix can be run without a restart. Fields are volatile: they are read on the
 * request hot path and written from the config endpoint thread.
 */
@Component
public class AblationConfig {

    private volatile boolean ragEnabled;
    private volatile boolean dynamicSearchEnabled;

    public AblationConfig(
            @Value("${kdiag.rag.enabled:true}") boolean ragEnabled,
            @Value("${kdiag.search.dynamic.enabled:true}") boolean dynamicSearchEnabled) {
        this.ragEnabled = ragEnabled;
        this.dynamicSearchEnabled = dynamicSearchEnabled;
    }

    public boolean isRagEnabled() {
        return ragEnabled;
    }

    public boolean isDynamicSearchEnabled() {
        // Dynamic search is only meaningful on top of RAG: in mode "none" the marker
        // contract is dropped from the prompt as well.
        return ragEnabled && dynamicSearchEnabled;
    }

    /** Returns the current mode as one of {@code none | static | dynamic}. */
    public String mode() {
        if (!ragEnabled) return "none";
        return dynamicSearchEnabled ? "dynamic" : "static";
    }

    /**
     * Sets both flags from a mode string ({@code none | static | dynamic}, case-insensitive).
     *
     * @throws IllegalArgumentException for any other value
     */
    public void setMode(String mode) {
        switch (mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> {
                this.ragEnabled = false;
                this.dynamicSearchEnabled = false;
            }
            case "static" -> {
                this.ragEnabled = true;
                this.dynamicSearchEnabled = false;
            }
            case "dynamic" -> {
                this.ragEnabled = true;
                this.dynamicSearchEnabled = true;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown RAG mode '" + mode + "' — expected none | static | dynamic");
        }
    }
}
