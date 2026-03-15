package com.example.controllers;

import com.example.entities.ChatAttachment;
import com.example.repositories.ChatAttachmentRepository;
import com.example.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat/attachments")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AttachmentController {

    @Autowired
    private ChatAttachmentRepository attachmentRepository;

    @GetMapping("/{id}")
    public ResponseEntity<?> getAttachmentMeta(@PathVariable Long id) {
        Optional<ChatAttachment> opt = attachmentRepository.findById(id);
        if (opt.isEmpty())
            return ResponseEntity.status(404).body(Map.of("message", "Attachment not found"));
        ChatAttachment a = opt.get();
        return ResponseEntity.ok(Map.of(
                "id", a.getId(),
                "conversationId", a.getConversationId(),
                "chatId", a.getChat() != null ? a.getChat().getId() : null,
                "name", a.getFileName(),
                "type", a.getMimeType(),
                "size", a.getSizeBytes(),
                "createdAt", a.getCreatedAt()));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<?> getAttachmentContent(@PathVariable Long id) {
        Optional<ChatAttachment> opt = attachmentRepository.findById(id);
        if (opt.isEmpty())
            return ResponseEntity.status(404).body(Map.of("message", "Attachment not found"));
        ChatAttachment a = opt.get();

        byte[] bytes = a.getContent();
        if ("gzip".equalsIgnoreCase(a.getContentEncoding())) {
            bytes = Utils.ungzip(bytes);
        }
        String content = new String(bytes != null ? bytes : new byte[0], StandardCharsets.UTF_8);

        return ResponseEntity.ok(Map.of(
                "id", a.getId(),
                "name", a.getFileName(),
                "type", a.getMimeType(),
                "size", a.getSizeBytes(),
                "content", content));
    }

}
