package com.example.controllers;

import com.example.entities.ChatAttachment;
import com.example.repositories.ChatAttachmentRepository;
import com.example.security.CurrentUser;
import com.example.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat/attachments")
public class AttachmentController {

    @Autowired
    private ChatAttachmentRepository attachmentRepository;

    /**
     * Ownership check: attachment ids are sequential Longs, trivially enumerable,
     * so without this any authenticated user could read anyone's file contents.
     * Same convention as conversation endpoints: 404 (not 403) so the API doesn't
     * leak which ids exist. When there is no authenticated principal (direct calls
     * in tests), the check is skipped — same fallback as CurrentUser.resolve().
     */
    private boolean notOwner(ChatAttachment a) {
        Long tokenUserId = CurrentUser.id();
        if (tokenUserId == null)
            return false;
        return a.getUser() == null || !tokenUserId.equals(a.getUser().getId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getAttachmentMeta(@PathVariable Long id) {
        Optional<ChatAttachment> opt = attachmentRepository.findById(id);
        if (opt.isEmpty() || notOwner(opt.get()))
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
        if (opt.isEmpty() || notOwner(opt.get()))
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
