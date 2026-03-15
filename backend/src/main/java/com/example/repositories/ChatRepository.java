package com.example.repositories;

import com.example.entities.Chat;
import com.example.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// <Tip de data, Tipul Primary Key-ului>
@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {
    List<Chat> findByUserOrderByCreatedAtDesc(User user);

    List<Chat> findByUser(User user);

    // Conversation-scoped helpers
    boolean existsByConversationId(String conversationId);

    List<Chat> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    void deleteByConversationId(String conversationId);
}
