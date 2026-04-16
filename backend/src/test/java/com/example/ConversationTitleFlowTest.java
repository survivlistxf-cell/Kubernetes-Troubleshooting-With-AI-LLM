package com.example;

import com.example.controllers.ChatController;
import com.example.entities.Conversation;
import com.example.entities.User;
import com.example.repositories.ConversationRepository;
import com.example.repositories.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal integration test for conversation title behavior.
 *
 * Notes:
 * - We call controller methods directly to avoid dealing with security filters.
 * - This uses the configured datasource; for easiest local runs, you can set an H2
 *   test config later. For now it validates wiring/logic.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConversationTitleFlowTest {

    @Autowired
    private ChatController chatController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Test
    void patchTitleMarksCustomAndPreventsAutoOverwrite() {
        // Arrange user
        User u = new User();
        u.setUsername("t");
        u.setEmail("t@example.com");
        u.setPassword("pw");
        u = userRepository.save(u);

        String convId = "conv-test-1";

    // Act: send first chat (will create conversation + auto-title)
    // If AI server is unavailable in test env, heuristic will be used.
        chatController.chat(Map.of(
                "userId", String.valueOf(u.getId()),
                "conversationId", convId,
                "message", "Salut, am o problema cu pods"
        ));

        Conversation c1 = conversationRepository.findById(convId).orElseThrow();
        assertNotNull(c1.getTitle());
    assertNotEquals("Salut, am o problema cu pods", c1.getTitle(), "Title should be a short summary, not full prompt");

        // Act: user edits title
        chatController.updateConversationTitle(convId, u.getId(), Map.of("title", "Titlu Custom"));

        Conversation c2 = conversationRepository.findById(convId).orElseThrow();
        assertEquals("Titlu Custom", c2.getTitle());
        assertTrue(Boolean.TRUE.equals(c2.getTitleCustom()));

        // Act: send another message (should NOT overwrite custom title)
        chatController.chat(Map.of(
                "userId", String.valueOf(u.getId()),
                "conversationId", convId,
                "message", "Inca un mesaj"
        ));

        Conversation c3 = conversationRepository.findById(convId).orElseThrow();
        assertEquals("Titlu Custom", c3.getTitle());
        assertTrue(Boolean.TRUE.equals(c3.getTitleCustom()));
    }
}
