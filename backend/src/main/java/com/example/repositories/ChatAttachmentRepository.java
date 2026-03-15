package com.example.repositories;

import com.example.entities.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

// <Tip de data, Tipul Primary Key-ului>
@Repository
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {
    List<ChatAttachment> findByChatIdOrderByIdAsc(Long chatId);

    List<ChatAttachment> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
