package com.kdiag.server.ollama;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Runs once after all beans are constructed (via {@link ApplicationRunner}) and
 * verifies that {@code ollama.num-ctx} is compatible with the model's reported
 * maximum context window.
 *
 * <p>Three outcomes:
 * <ul>
 *   <li><b>ERROR</b> – configured {@code num_ctx} exceeds the model's max.
 *       Ollama will silently truncate the context window.</li>
 *   <li><b>WARN</b>  – configured {@code num_ctx} is well below a quarter of the
 *       model's max; there is unused capacity.</li>
 *   <li><b>INFO</b>  – everything looks reasonable.</li>
 * </ul>
 *
 * <p>If Ollama is not reachable at startup (common in CI / unit-test runs) the
 * check is skipped gracefully — {@link OllamaClient#queryModelMaxContext()} returns
 * {@link Optional#empty()} and a single WARN is logged.
 */
@Component
public class OllamaStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OllamaStartupCheck.class);

    private final OllamaClient ollama;
    private final int configuredNumCtx;

    public OllamaStartupCheck(OllamaClient ollama,
                              @Value("${ollama.num-ctx:8192}") int numCtx) {
        this.ollama           = ollama;
        this.configuredNumCtx = numCtx;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<Integer> maxCtxOpt = ollama.queryModelMaxContext();

        if (maxCtxOpt.isEmpty()) {
            log.warn("Ollama /api/show did not return a context_length — cannot verify num_ctx. " +
                     "Configured num_ctx = {}", configuredNumCtx);
            return;
        }

        int maxCtx = maxCtxOpt.get();

        if (configuredNumCtx > maxCtx) {
            log.error("ollama.num-ctx={} exceeds the model's max context_length={}. " +
                      "Ollama will silently truncate. Lower num_ctx in application.properties.",
                      configuredNumCtx, maxCtx);
        } else if (configuredNumCtx < maxCtx / 4) {
            log.warn("ollama.num-ctx={} is well below model's max={} — " +
                     "you have room to grow if responses feel truncated.",
                     configuredNumCtx, maxCtx);
        } else {
            log.info("Ollama context OK: configured num_ctx={}, model max={}",
                     configuredNumCtx, maxCtx);
        }
    }
}
