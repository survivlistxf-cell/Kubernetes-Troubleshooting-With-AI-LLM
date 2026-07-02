package com.example.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link JwtService} — no Spring context required. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-long-enough-for-hs256-0123456789";

    @Test
    void generatesValidToken_carryingUsernameAndUserId() {
        JwtService jwt = new JwtService(SECRET, 86_400_000L);

        String token = jwt.generateToken(42L, "alice");

        assertTrue(jwt.isValid(token));
        assertEquals("alice", jwt.extractUsername(token));
        assertEquals(42, jwt.parseClaims(token).get(JwtService.CLAIM_USER_ID, Integer.class));
    }

    @Test
    void rejectsGarbageAndTamperedTokens() {
        JwtService jwt = new JwtService(SECRET, 86_400_000L);

        assertFalse(jwt.isValid("not-a-jwt"));

        String token = jwt.generateToken(1L, "bob");
        String tampered = token.substring(0, token.length() - 2) + "xy";
        assertFalse(jwt.isValid(tampered), "a token with a broken signature must be rejected");
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = new JwtService(SECRET, 86_400_000L).generateToken(1L, "bob");
        JwtService other = new JwtService("a-totally-different-secret-key-also-32+bytes-long-xxxx", 86_400_000L);

        assertFalse(other.isValid(token), "tokens from another secret must not validate");
    }

    @Test
    void rejectsExpiredToken() {
        // Negative lifetime => the token is already expired the moment it is issued.
        JwtService jwt = new JwtService(SECRET, -1_000L);
        String expired = jwt.generateToken(1L, "carol");

        assertFalse(jwt.isValid(expired), "expired token must be rejected");
    }
}
