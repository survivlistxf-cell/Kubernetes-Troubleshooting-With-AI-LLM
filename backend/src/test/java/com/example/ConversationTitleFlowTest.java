package com.example;

import com.example.controllers.ChatController;
import com.example.entities.Conversation;
import com.example.entities.User;
import com.example.repositories.ConversationRepository;
import com.example.repositories.UserRepository;
import com.example.services.ChatService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal integration test for conversation title behavior.
 *
 * Notes:
 * - Runs on the {@code test} profile (in-memory H2, create-drop), like
 *   {@link com.example.security.AuthFlowIntegrationTest}. Without it, the test wrote
 *   into the real PostgreSQL and failed on re-runs with duplicate-key violations
 *   (user "t" left over from previous runs).
 * - Persistence goes through ChatService directly (not ChatController.chat()): since the
 *   gateway returns 503 without persisting when the AI server is unreachable — the honest
 *   behavior — the controller path cannot be exercised without a live/mocked AI server.
 *   Title logic is what this test covers, and it is independent of the AI call.
 * - Controller methods are called directly (no security filters); without an
 *   authenticated principal, the request-supplied userId is used as-is.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConversationTitleFlowTest {

    @Autowired
    private ChatController chatController;

    @Autowired
    private ChatService chatService;

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
        String userId = String.valueOf(u.getId());

        String convId = "conv-test-1";

        // Act: persist first exchange (creates conversation + heuristic title).
        // AI title generation falls back to deriveTitle() when the AI server is absent.
        chatService.persistChat(userId, convId, "Salut, am o problema cu pods", "raspuns AI", null);
        chatService.autoGenerateTitleIfNeeded(convId, userId, "test-req-1");

        Conversation c1 = conversationRepository.findById(convId).orElseThrow();
        assertNotNull(c1.getTitle());
        assertNotEquals("Salut, am o problema cu pods", c1.getTitle(),
                "Title should be a short summary, not full prompt");

        // Act: user edits title
        chatController.updateConversationTitle(convId, u.getId(), Map.of("title", "Titlu Custom"));

        Conversation c2 = conversationRepository.findById(convId).orElseThrow();
        assertEquals("Titlu Custom", c2.getTitle());
        assertTrue(Boolean.TRUE.equals(c2.getTitleCustom()));

        // Act: persist another exchange (should NOT overwrite custom title)
        chatService.persistChat(userId, convId, "Inca un mesaj", "alt raspuns", null);
        chatService.autoGenerateTitleIfNeeded(convId, userId, "test-req-2");

        Conversation c3 = conversationRepository.findById(convId).orElseThrow();
        assertEquals("Titlu Custom", c3.getTitle());
        assertTrue(Boolean.TRUE.equals(c3.getTitleCustom()));
    }
}
