package com.example.repositories;

import com.example.entities.Conversation;
import com.example.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// <Tip de data, Tipul Primary Key-ului>
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByUserOrderByUpdatedAtDesc(User user);
}
