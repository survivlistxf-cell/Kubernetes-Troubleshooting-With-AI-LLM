package com.example.services;

import com.example.entities.Chat;
import com.example.entities.ChatAttachment;
import com.example.entities.User;
import com.example.repositories.ChatAttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

@Service
public class AttachmentService {

    // Soft limits to keep DB healthy (can be made configurable)
    public static final long MAX_ATTACHMENT_BYTES = 2L * 1024L * 1024L; // 2MB
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 12;

    @Autowired
    private ChatAttachmentRepository attachmentRepository;

    public List<ChatAttachment> saveAttachmentsFromRequest(
            String conversationId,
            User user,
            Chat chat,
            Object attachmentsObj) {
        List<ChatAttachment> saved = new ArrayList<>();
        if (!(attachmentsObj instanceof List<?> list) || list.isEmpty())
            return saved;

        int count = 0;
        for (Object item : list) {
            if (count >= MAX_ATTACHMENTS_PER_MESSAGE)
                break;
            if (!(item instanceof Map<?, ?> map))
                continue;

            String name = safeStr(map.get("name"));
            String type = safeStr(map.get("type"));
            Object sizeObj = map.get("size");
            String content = safeStr(map.get("content"));

            if (name == null || name.isBlank())
                continue;
            if (type == null || type.isBlank())
                type = "text/plain";

            long sizeBytes = (sizeObj instanceof Number n) ? n.longValue()
                    : (content != null ? content.getBytes(StandardCharsets.UTF_8).length : 0);

            if (sizeBytes <= 0)
                continue;
            if (sizeBytes > MAX_ATTACHMENT_BYTES) {
                // Skip oversized attachments; could also truncate or store metadata only
                continue;
            }
            if (content == null)
                content = "";

            byte[] raw = content.getBytes(StandardCharsets.UTF_8);
            String sha256 = sha256Hex(raw);

            // Compress text to reduce DB bloat
            byte[] gz = gzip(raw);
            // Daca marimea fisierului comprimat este mai mare decat fisierul in sine,
            // folosim fisierul necomprimat
            boolean useGzip = gz != null && gz.length < raw.length;

            ChatAttachment att = new ChatAttachment();
            att.setConversationId(conversationId);
            att.setUser(user);
            att.setChat(chat);
            att.setFileName(name);
            att.setMimeType(type);
            att.setSizeBytes(sizeBytes);
            att.setSha256(sha256);
            att.setContentEncoding(useGzip ? "gzip" : "identity");
            att.setContent(useGzip ? gz : raw);
            att.setCreatedAt(LocalDateTime.now());

            saved.add(attachmentRepository.save(att));
            count++;
        }
        return saved;
    }

    public static String safeStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(bytes);
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(raw);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
