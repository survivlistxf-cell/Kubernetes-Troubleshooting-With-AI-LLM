package com.example.security;

import com.example.entities.User;
import com.example.repositories.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end JWT auth flow over the real Spring Security filter chain (H2 via the
 * {@code test} profile). Verifies: login issues a token; a protected endpoint returns
 * 200 with a valid token and 401 without one or with a tampered one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    /** A protected (authenticated) read endpoint used to probe access control. */
    private static final String PROTECTED_URL = "/api/clusters";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void seedUser() {
        userRepository.findByEmail("login@test.local").orElseGet(() -> {
            User u = new User();
            u.setUsername("login-tester");
            u.setEmail("login@test.local");
            u.setPassword(passwordEncoder.encode("secret123"));
            return userRepository.save(u);
        });
    }

    @Test
    void login_returnsJwtToken() throws Exception {
        String token = loginAndGetToken();
        assertNotNull(token);
        assertFalse(token.isBlank());
        // A JWT has three dot-separated parts.
        assertEquals(3, token.split("\\.").length, "login must return a real JWT");
    }

    @Test
    void protectedEndpoint_withValidToken_returns200() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get(PROTECTED_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_URL).header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpoints_areAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    // -- helpers --------------------------------------------------------------

    private String loginAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@test.local\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return body.path("token").asText(null);
    }
}
