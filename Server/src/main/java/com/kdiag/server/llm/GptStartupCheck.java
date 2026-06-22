package com.kdiag.server.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Runs once after all beans are constructed (via {@link ApplicationRunner}) and
 * verifies that {@code llm.chat.num-ctx} (the local prompt-budget knob) is compatible
 * with the chat model's reported maximum context window.
 *
 * <p>Three outcomes:
 * <ul>
 *   <li><b>ERROR</b> – configured {@code num_ctx} exceeds the model's max.</li>
 *   <li><b>WARN</b>  – configured {@code num_ctx} is well below a quarter of the
 *       model's max; there is unused capacity.</li>
 *   <li><b>INFO</b>  – everything looks reasonable.</li>
 * </ul>
 *
 * <p>With the OpenAI-compatible gpt-oss endpoint the max context length is not
 * exposed, so {@link GptChatClient#queryModelMaxContext()} returns
 * {@link Optional#empty()} and the check is a graceful no-op (single INFO logged).
 */
@Component
public class GptStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GptStartupCheck.class);

    private final GptChatClient gpt;
    private final int configuredNumCtx;

    public GptStartupCheck(GptChatClient gpt,
                           @Value("${llm.chat.num-ctx:8192}") int numCtx) {
        this.gpt              = gpt;
        this.configuredNumCtx = numCtx;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<Integer> maxCtxOpt = gpt.queryModelMaxContext();

        if (maxCtxOpt.isEmpty()) {
            // Expected with gpt-oss: the OpenAI endpoint exposes no context_length,
            // so this check is a graceful no-op. num_ctx is a local prompt-budget knob only.
            log.info("Model max context unavailable (OpenAI-compatible endpoint) — " +
                     "num_ctx is used only for local prompt budgeting. Configured num_ctx = {}",
                     configuredNumCtx);
            return;
        }

        int maxCtx = maxCtxOpt.get();

        if (configuredNumCtx > maxCtx) {
            log.error("llm.chat.num-ctx={} exceeds the model's max context_length={}. " +
                      "The prompt budget may overflow the window. Lower num_ctx in application.properties.",
                      configuredNumCtx, maxCtx);
        } else if (configuredNumCtx < maxCtx / 4) {
            log.warn("llm.chat.num-ctx={} is well below model's max={} — " +
                     "you have room to grow if responses feel truncated.",
                     configuredNumCtx, maxCtx);
        } else {
            log.info("Chat context OK: configured num_ctx={}, model max={}",
                     configuredNumCtx, maxCtx);
        }
    }
}
