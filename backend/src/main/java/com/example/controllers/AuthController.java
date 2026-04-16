package com.example.controllers;

import com.example.entities.User;
import com.example.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AuthController {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String email = request.get("email");
        String password = request.get("password");
        
        // Validate input
        if (username == null || email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing required fields"));
        }
        
        // Check if user already exists
        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email already registered"));
        }
        
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username already taken"));
        }
        
        // Create new user
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        
        userRepository.save(user);
        
        return ResponseEntity.ok(Map.of("message", "User registered successfully"));
    }
    
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");
        
        // Validate input
        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email and password required"));
        }
        
        // Find user by email
        Optional<User> userOpt = userRepository.findByEmail(email);
        
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid email or password"));
        }
        
        User user = userOpt.get();
        
        // Check password (in production, use proper JWT token generation)
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid email or password"));
        }
        
        // Return success with user info (token should be JWT in production)
        Map<String, Object> response = new HashMap<>();
        response.put("token", "token_" + user.getId() + "_" + System.currentTimeMillis());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("userId", user.getId());
        
        return ResponseEntity.ok(response);
    }
}
