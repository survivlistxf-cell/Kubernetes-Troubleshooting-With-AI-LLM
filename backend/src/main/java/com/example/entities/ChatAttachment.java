package com.example.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_attachments", indexes = {
        @Index(name = "idx_att_conversation_id", columnList = "conversation_id"),
        @Index(name = "idx_att_chat_id", columnList = "chat_id"),
        @Index(name = "idx_att_user_id", columnList = "user_id"),
        @Index(name = "idx_att_created_at", columnList = "created_at"),
        @Index(name = "idx_att_sha256", columnList = "sha256")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 100)
    private String conversationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id")
    private Chat chat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 120)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "content_encoding", nullable = false, length = 20)
    private String contentEncoding; // "identity" | "gzip"

    // Large Object
    @Lob
    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = java.time.LocalDateTime.now();
    }
}
