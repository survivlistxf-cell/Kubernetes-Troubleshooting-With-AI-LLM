package com.kdiag.server.ai.stream;

/**
 * A discriminated unit emitted by {@link com.kdiag.server.ai.AiEngine#solveStream}.
 *
 * <ul>
 *   <li>{@link Type#TOKEN}  – one raw text fragment produced by the LLM.</li>
 *   <li>{@link Type#STATUS} – a UI progress notification emitted while a
 *       {@code [NEEDS_SEARCH:]} dynamic-search is running (before the second
 *       Ollama call starts).</li>
 * </ul>
 *
 * <p>Consumers should switch on {@link #type()} and ignore unknown type values so
 * the protocol remains forward-compatible.
 *
 * <p>Use the static factories {@link #token(String)} and {@link #status(String, String)}
 * rather than the canonical constructor to keep call-sites readable.
 */
public record StreamChunk(Type type, String text, String code, String label) {

    public enum Type { TOKEN, STATUS }

    /**
     * Creates a plain LLM text fragment.
     *
     * @param text raw token text (may contain leading/trailing whitespace)
     */
    public static StreamChunk token(String text) {
        return new StreamChunk(Type.TOKEN, text, null, null);
    }

    /**
     * Creates a UI progress event.
     *
     * @param code  stable English identifier for the event (e.g. {@code "searching"},
     *              {@code "search_done"}, {@code "search_empty"})
     * @param label human-readable label in Romanian shown in the frontend
     */
    public static StreamChunk status(String code, String label) {
        return new StreamChunk(Type.STATUS, null, code, label);
    }
}
