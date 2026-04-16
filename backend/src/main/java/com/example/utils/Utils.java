package com.example.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class Utils {

    // ── ChatController & Generic Utilities ──

    /**
     * Converts an object to its string representation safely.
     * Used primarily by ChatController and ContextController for request parsing.
     */
    public static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Extracts a user message from a flexible request map.
     * Supports both direct string "message" and structured "{ message: { text: ...
     * } }".
     * Used by ChatController.
     */
    public static String extractUserMessage(Map<String, Object> request) {
        // mesaj simplu
        Object msg = request.get("message");
        if (msg instanceof String s)
            return s;
        // mesaj structurat, daca va fi nevoie pe viitor
        if (msg instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null)
                return String.valueOf(text);
            Object content = map.get("content");
            if (content != null)
                return String.valueOf(content);
        }
        return null;
    }

    // ── AttachmentController Utilities ──

    /**
     * Decompresses a GZIP-encoded byte array.
     * Used by AttachmentController for retrieving persisted attachment content.
     */
    public static byte[] ungzip(byte[] gz) {
        try {
            if (gz == null)
                return null;
            ByteArrayInputStream bais = new ByteArrayInputStream(gz);
            try (GZIPInputStream gis = new GZIPInputStream(bais)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = gis.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
