package com.example.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates signed JWTs (HMAC-SHA256).
 *
 * <p>The signing secret and token lifetime are taken from configuration
 * ({@code security.jwt.secret} / {@code security.jwt.expiration-ms}), both overridable
 * via environment variables.
 */
@Service
public class JwtService {

    /** Claim holding the numeric user id (the subject carries the username). */
    public static final String CLAIM_USER_ID = "uid";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-ms:86400000}") long expirationMs) {
        // HS256 requires a key of at least 256 bits (32 bytes).
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least 32 bytes for HS256; got " + keyBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    /**
     * Generate a signed token with {@code subject = username} and a {@code uid} claim.
     */
    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    /**
     * Parse and verify a token, returning its claims.
     *
     * @throws JwtException if the signature is invalid, the token is malformed, or expired
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** @return the username (subject) of a valid token. */
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /** @return {@code true} when the token is well-formed, correctly signed and not expired. */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
