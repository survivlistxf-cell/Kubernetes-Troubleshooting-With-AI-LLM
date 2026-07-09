package com.example.security;

/**
 * Principal stored in the {@code SecurityContext} after JWT validation.
 * Carries the numeric user id (the {@code uid} claim) alongside the username,
 * so controllers can authorize against the token instead of trusting
 * client-supplied {@code userId} values (IDOR prevention).
 */
public record AuthenticatedUser(Long id, String username) {

    @Override
    public String toString() {
        // Spring logs the principal in places; keep it terse.
        return username + "#" + id;
    }
}
