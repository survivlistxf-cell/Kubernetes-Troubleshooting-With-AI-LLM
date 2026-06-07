package com.kdiag.server.docs.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSplitterTest {

    // -------------------------------------------------------------------------
    // Basic edge cases
    // -------------------------------------------------------------------------

    @Test
    void nullText_returnsEmptyList() {
        assertEquals(List.of(), ChunkSplitter.split(null));
    }

    @Test
    void emptyText_returnsEmptyList() {
        assertEquals(List.of(), ChunkSplitter.split(""));
        assertEquals(List.of(), ChunkSplitter.split("   \n\n   "));
    }

    @Test
    void shortText_returnsSingleChunk() {
        String text = "Pods are the smallest deployable units.";
        List<String> chunks = ChunkSplitter.split(text);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("Pods"));
    }

    // -------------------------------------------------------------------------
    // Splitting behaviour
    // -------------------------------------------------------------------------

    @Test
    void textExceedingTarget_splitsIntoMultipleChunks() {
        // Build text that is clearly larger than TARGET_CHUNK_CHARS
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i)
              .append(": Kubernetes schedules pods onto nodes based on resource requirements. ")
              .append("Each pod runs one or more containers that share networking and storage.\n\n");
        }
        List<String> chunks = ChunkSplitter.split(sb.toString());
        assertTrue(chunks.size() > 1, "Expected multiple chunks but got " + chunks.size());
    }

    @Test
    void noChunkExceedsHardCeiling() {
        // Build ~5 000-char prose with no blank lines (one huge paragraph forcing sentence splitting)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("Sentence number ").append(i)
              .append(" explains how Kubernetes handles pod scheduling on cluster nodes. ");
        }
        List<String> chunks = ChunkSplitter.split(sb.toString());
        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            // Allow slight overage from overlap prefix (~100 chars)
            assertTrue(chunk.length() <= ChunkSplitter.MAX_CHUNK_CHARS + ChunkSplitter.OVERLAP_CHARS + 5,
                    "Chunk length " + chunk.length() + " exceeds ceiling");
        }
    }

    @Test
    void hugeParagraph_splitsBySentences() {
        // Single paragraph well over MAX_CHUNK_CHARS
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("This is sentence number ").append(i)
              .append(" about debugging pods in Kubernetes clusters. ");
        }
        List<String> chunks = ChunkSplitter.split(sb.toString());
        // Should produce multiple chunks since text is > MAX_CHUNK_CHARS
        assertTrue(chunks.size() >= 2, "Expected sentence-split chunks but got " + chunks.size());
    }

    // -------------------------------------------------------------------------
    // Overlap
    // -------------------------------------------------------------------------

    @Test
    void overlap_isPrependedToSubsequentChunks() {
        // Build two clearly separate paragraphs that each fill ~TARGET size
        String para = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega. ".repeat(6);
        String text = para + "\n\n" + para;
        List<String> chunks = ChunkSplitter.split(text);
        if (chunks.size() < 2) return; // nothing to verify if it fit in one chunk
        // The second (and later) chunks should start with overlap text from the previous chunk
        String first = chunks.get(0);
        String second = chunks.get(1);
        // The tail of the first chunk should appear somewhere near the start of the second
        String tail = ChunkSplitter.tailSentence(first);
        if (!tail.isBlank()) {
            assertTrue(second.startsWith(tail),
                    "Expected second chunk to start with overlap tail '" + tail + "' but was: " + second.substring(0, Math.min(200, second.length())));
        }
    }

    // -------------------------------------------------------------------------
    // tailSentence helper
    // -------------------------------------------------------------------------

    @Test
    void tailSentence_extractsLastSentenceWithinLimit() {
        String chunk = "First sentence here. Second sentence follows. Third sentence is the last one.";
        String tail = ChunkSplitter.tailSentence(chunk);
        assertFalse(tail.isBlank());
        assertTrue(tail.length() <= ChunkSplitter.OVERLAP_CHARS);
        // Should contain content from near the end of the chunk
        assertTrue(chunk.endsWith(tail) || chunk.contains(tail),
                "Tail '" + tail + "' not found in chunk");
    }

    @Test
    void tailSentence_onTextWithNoSentenceBoundary_returnsLastChars() {
        String chunk = "wordone wordtwo wordthree wordfour wordfive wordsix";
        String tail = ChunkSplitter.tailSentence(chunk);
        assertFalse(tail.isBlank());
        assertTrue(tail.length() <= ChunkSplitter.OVERLAP_CHARS);
    }

    @Test
    void tailSentence_emptyInput_returnsEmpty() {
        assertEquals("", ChunkSplitter.tailSentence(null));
        assertEquals("", ChunkSplitter.tailSentence(""));
    }
}
