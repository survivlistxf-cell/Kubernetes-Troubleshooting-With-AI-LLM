package com.example.repositories;

import com.example.entities.ConversationContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

// <Tip de data, Tipul Primary Key-ului>
public interface ConversationContextRepository extends JpaRepository<ConversationContext, Long> {
    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    List<ConversationContext> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    void deleteByConversationId(String conversationId);

    Optional<ConversationContext> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
