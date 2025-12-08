package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.context.annotation.Bean;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000", "http://localhost:8080", "*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.POST})
class ApiController {

    @GetMapping("/api/hello")
    public String hello() {
        return "Hello from Spring Boot Backend!";
    }

    @GetMapping("/api/status")
    public String status() {
        return "Backend is running!";
    }

    @PostMapping("/api/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String userMessage = request.get("message");
        
        // Simple AI response logic (can be replaced with real AI service)
        String response = generateResponse(userMessage);
        
        Map<String, String> result = new HashMap<>();
        result.put("response", response);
        return result;
    }

    @GetMapping("/api/scan-pods")
    public Map<String, Object> scanPods() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> pods = new ArrayList<>();

        try {
            // Check if kubectl is installed
            if (!isKubectlInstalled()) {
                result.put("error", "kubectl is not installed or not in PATH");
                result.put("pods", pods);
                return result;
            }

            // Run kubectl get pods
            String command = "kubectl get pods --all-namespaces -o wide";
            String output = executeCommand(command);

            if (output == null || output.isEmpty()) {
                result.put("message", "No pods found or kubectl not accessible");
                result.put("pods", pods);
                return result;
            }

            // Parse kubectl output
            String[] lines = output.split("\n");
            for (int i = 1; i < lines.length; i++) { // Skip header
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    Map<String, String> pod = new HashMap<>();
                    pod.put("namespace", parts[0]);
                    pod.put("name", parts[1]);
                    pod.put("ready", parts.length > 2 ? parts[2] : "N/A");
                    pod.put("status", parts.length > 3 ? parts[3] : "N/A");
                    pod.put("restarts", parts.length > 4 ? parts[4] : "0");
                    pod.put("age", parts.length > 5 ? parts[5] : "N/A");
                    pod.put("node", parts.length > 6 ? parts[6] : "N/A");
                    pod.put("containers", parts.length > 2 ? parts[2].split("/")[1] : "N/A");
                    pods.add(pod);
                }
            }

            result.put("success", true);
            result.put("pods", pods);
            result.put("count", pods.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("pods", pods);
        }

        return result;
    }

    private boolean isKubectlInstalled() {
        try {
            Process process = Runtime.getRuntime().exec(isWindows() ? new String[]{"where", "kubectl"} : new String[]{"which", "kubectl"});
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String executeCommand(String command) {
        try {
            Process process;
            if (isWindows()) {
                process = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", command});
            } else {
                process = Runtime.getRuntime().exec(new String[]{"/bin/bash", "-c", command});
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            System.err.println("Error executing command: " + e.getMessage());
            return null;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @CrossOrigin(origins = "*")
    @GetMapping("/api/scan-nodes")
    public Map<String, Object> scanNodes() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> nodes = new ArrayList<>();

        try {
            // Check if kubectl is installed
            if (!isKubectlInstalled()) {
                result.put("error", "kubectl is not installed or not in PATH");
                result.put("nodes", nodes);
                return result;
            }

            // Run kubectl get nodes
            String command = "kubectl get nodes -o wide";
            String output = executeCommand(command);

            if (output == null || output.isEmpty()) {
                result.put("message", "No nodes found or kubectl not accessible");
                result.put("nodes", nodes);
                return result;
            }

            // Parse kubectl output
            String[] lines = output.split("\n");
            for (int i = 1; i < lines.length; i++) { // Skip header
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    Map<String, String> node = new HashMap<>();
                    node.put("name", parts[0]);
                    node.put("status", parts.length > 1 ? parts[1] : "N/A");
                    node.put("roles", parts.length > 2 ? parts[2] : "N/A");
                    node.put("age", parts.length > 3 ? parts[3] : "N/A");
                    node.put("version", parts.length > 4 ? parts[4] : "N/A");
                    node.put("internalIp", parts.length > 5 ? parts[5] : "N/A");
                    node.put("externalIp", parts.length > 6 ? parts[6] : "N/A");
                    nodes.add(node);
                }
            }

            result.put("success", true);
            result.put("nodes", nodes);
            result.put("count", nodes.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("nodes", nodes);
        }

        return result;
    }

    private String generateResponse(String message) {
        // Simple keyword-based responses for now
        String lowerMessage = message.toLowerCase();
        
        if (lowerMessage.contains("kubernetes") || lowerMessage.contains("k8s")) {
            return "Kubernetes is an open-source container orchestration platform that automates many of the manual processes involved in deploying, managing, and scaling containerized applications.";
        } else if (lowerMessage.contains("docker")) {
            return "Docker is a containerization platform that packages your application and all its dependencies into a standardized unit called a container, making it easier to deploy across different environments.";
        } else if (lowerMessage.contains("hello") || lowerMessage.contains("hi")) {
            return "Hello! I'm Kubexplain, your AI assistant for Kubernetes and cloud infrastructure questions. How can I help you today?";
        } else if (lowerMessage.contains("help")) {
            return "I can help you with questions about:\n- Kubernetes (K8s)\n- Docker\n- Container orchestration\n- Cloud infrastructure\n- DevOps practices\n\nWhat would you like to know?";
        } else {
            return "That's an interesting question! I'm still learning about that topic. Please try asking me about Kubernetes, Docker, or cloud infrastructure. You can also type 'help' for more information.";
        }
    }
}
