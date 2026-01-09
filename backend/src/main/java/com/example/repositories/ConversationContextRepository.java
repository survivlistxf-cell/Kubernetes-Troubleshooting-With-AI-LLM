package com.example.repositories;

import com.example.entities.ConversationContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationContextRepository extends JpaRepository<ConversationContext, Long> {
    List<ConversationContext> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    void deleteByConversationId(String conversationId);

    Optional<ConversationContext> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
