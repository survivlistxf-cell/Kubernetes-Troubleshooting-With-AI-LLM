package com.kdiag.server.docs.index;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

//text brut (50.000 chars)
//     │
//     ▼  PARA_SPLIT.split (la \n\n)
//     │
// paragraphs[] (50 paragrafe, fiecare 100-3000 chars)
//     │
//     ▼  breakIntoUnits  ←── aici comparăm fiecare paragraf cu 1800
//     │                      (sparge cele lungi în sub-paragrafe)
//     │
// units[] (60 unități, fiecare GARANTAT ≤ 1800 chars)
//     │
//     ▼  packUnits  ←── aici comparăm BUFFER-UL curent cu 1200/1800
//     │                 (combinăm unități mici în chunks)
//     │
// chunks[] (~40 chunks, fiecare 1000-1800 chars)
//     │
//     ▼  addOverlap
//     │
// chunks finale (~40 chunks, fiecare cu ~100 chars overlap la început)

final class ChunkSplitter {

    static final int TARGET_CHUNK_CHARS = 1200;
    static final int MAX_CHUNK_CHARS = 1800;
    static final int OVERLAP_CHARS = 100;

    private static final Pattern PARA_SPLIT = Pattern.compile("\\n{2,}");   // 2 sau mai multe newline-uri  
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    private ChunkSplitter() {}

    static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> units = breakIntoUnits(text.trim());
        if (units.isEmpty()) return List.of();

        List<String> rawChunks = packUnits(units);
        return addOverlap(rawChunks);
    }

    // --- step 1: break text into paragraph-sized (or sentence-sized) units ---

    private static List<String> breakIntoUnits(String text) {
        String[] paragraphs = PARA_SPLIT.split(text);
        List<String> units = new ArrayList<>();
        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (p.length() <= MAX_CHUNK_CHARS) {
                units.add(p);
            } else {
                splitBySentences(p, units);
            }
        }
        return units;
    }

    private static void splitBySentences(String text, List<String> out) {
        String[] sentences = SENTENCE_SPLIT.split(text);
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            s = s.trim();
            if (s.isEmpty()) continue;
            String sep = current.length() > 0 ? " " : "";
            if (current.length() + sep.length() + s.length() > MAX_CHUNK_CHARS && current.length() > 0) {
                out.add(current.toString().trim());
                current = new StringBuilder(s);
            } else {
                current.append(sep).append(s);
            }
        }
        if (current.length() > 0) out.add(current.toString().trim());
    }

    // --- step 2: greedily pack units into chunks ---

    private static List<String> packUnits(List<String> units) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String unit : units) {
            String sep = current.length() > 0 ? "\n\n" : "";
            int projected = current.length() + sep.length() + unit.length();

            if (projected > MAX_CHUNK_CHARS && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder(unit);
            } else {
                current.append(sep).append(unit);
            }

            if (current.length() >= TARGET_CHUNK_CHARS) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
        }

        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks;
    }

    // --- step 3: prepend ~100-char tail of previous chunk as overlap ---

    private static List<String> addOverlap(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;
        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String tail = tailSentence(chunks.get(i - 1));
            result.add(tail.isEmpty() ? chunks.get(i) : tail + "\n\n" + chunks.get(i));
        }
        return result;
    }

    /**
     * Returns the last full sentence from chunk that fits within OVERLAP_CHARS.
     * Falls back to last OVERLAP_CHARS chars of text if no sentence boundary is found.
     */
    static String tailSentence(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        // Inspect only the last portion to avoid O(n) scans on huge chunks
        int scanStart = Math.max(0, chunk.length() - OVERLAP_CHARS * 4);
        String tail = chunk.substring(scanStart);

        // Walk backwards to find the last sentence boundary (. ! ?) followed by whitespace
        for (int i = tail.length() - 2; i >= 0; i--) {
            char c = tail.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(tail.charAt(i + 1))) {
                String sentence = tail.substring(i + 1).trim();
                if (!sentence.isEmpty()) {
                    return sentence.length() <= OVERLAP_CHARS ? sentence : sentence.substring(0, OVERLAP_CHARS);
                }
            }
        }

        // No sentence boundary found — just take the last OVERLAP_CHARS chars
        String plain = tail.trim();
        return plain.length() <= OVERLAP_CHARS ? plain : plain.substring(plain.length() - OVERLAP_CHARS);
    }
}
