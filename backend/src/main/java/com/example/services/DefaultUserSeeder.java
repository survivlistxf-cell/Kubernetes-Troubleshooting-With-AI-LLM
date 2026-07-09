package com.example.services;

import com.example.entities.User;
import com.example.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a single default user on a fresh database so the frontend (which sends a fixed
 * userId) keeps working after a clean deploy, without a manual /register step.
 *
 * <p>Idempotent: it only inserts when the users table is empty. On a fresh table the
 * IDENTITY column assigns id=1, which matches the frontend's default/stored session.
 * Credentials default to admin/admin and can be overridden via environment variables.
 * Change the default password for any non-local deployment.
 */
@Component
@Order(1)
public class DefaultUserSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DefaultUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DefaultUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            logger.info("[UserSeed] Users already exist, skipping default-user seed.");
            return;
        }

        String username = System.getenv().getOrDefault("DEFAULT_USER_NAME", "admin");
        String email    = System.getenv().getOrDefault("DEFAULT_USER_EMAIL", "admin@kubexplain.local");
        String password = System.getenv().getOrDefault("DEFAULT_USER_PASSWORD", "admin");

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user = userRepository.save(user);

        logger.info("[UserSeed] Seeded default user id={} (email={}). "
                + "Password comes from DEFAULT_USER_PASSWORD (default 'admin') — change it for non-local deployments.",
                user.getId(), email);
    }
}
