package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.bind.annotation.GetMapping;
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
@ComponentScan(basePackages = {"com.example", "com.example.controllers"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("*"));
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

    @GetMapping("/api/scan-pods")
    public Map<String, Object> scanPods() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> pods = new ArrayList<>();

        try {
            // First check if kubectl is available without waiting too long
            if (!isKubectlInstalledQuick()) {
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                result.put("pods", pods);
                result.put("success", false);
                return result;
            }

            // Run kubectl get pods with timeout
            String command = "kubectl get pods --all-namespaces -o wide";
            String output = executeCommandWithTimeout(command, 10); // 10 second timeout

            if (output == null || output.isEmpty()) {
                result.put("message", "No pods found or Kubernetes cluster not accessible");
                result.put("pods", pods);
                result.put("success", false);
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
                    // kubectl get pods -o wide columns normally:
                    // NAMESPACE NAME READY STATUS RESTARTS AGE IP NODE NOMINATED_NODE READINESS_GATES
                    // But RESTARTS can include the last restart time in parentheses, e.g.:
                    //   RESTARTS: "3 (19h ago)" => tokens: "3", "(19h", "ago)"
                    int idx = 4;

                    String restarts = parts.length > idx ? parts[idx] : "0";
                    idx++;
                    if (parts.length > idx && parts[idx].startsWith("(")) {
                        // Skip "(19h" and "ago)" (and any extra tokens until we see a token ending with ')')
                        while (parts.length > idx && !parts[idx].endsWith(")")) {
                            idx++;
                        }
                        if (parts.length > idx) {
                            idx++;
                        }
                    }
                    pod.put("restarts", restarts);

                    // AGE token is now at idx
                    String rawAge = parts.length > idx ? parts[idx] : "N/A";
                    pod.put("age", rawAge == null ? "N/A" : rawAge.replace("(", "").replace(")", ""));
                    idx++;

                    // IP then NODE
                    pod.put("ip", parts.length > idx ? parts[idx] : "N/A");
                    idx++;
                    pod.put("node", parts.length > idx ? parts[idx] : "N/A");
                    pod.put("containers", parts.length > 2 ? parts[2].split("/")[1] : "N/A");
                    pods.add(pod);
                }
            }

            result.put("success", true);
            result.put("pods", pods);
            result.put("count", pods.size());
        } catch (Exception e) {
            System.err.println("Scan pods error: " + e.getMessage());
            result.put("error", "Error scanning pods: " + e.getMessage());
            result.put("pods", pods);
            result.put("success", false);
        }

        return result;
    }


    private boolean isKubectlInstalledQuick() {
        try {
            // Quick check - just see if kubectl responds quickly
            ProcessBuilder pb = new ProcessBuilder("kubectl", "version", "--client", "-o", "json");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Wait max 3 seconds
            boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            
            return process.exitValue() == 0;
        } catch (Exception e) {
            System.err.println("kubectl quick check failed: " + e.getMessage());
            return false;
        }
    }

    private String executeCommandWithTimeout(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb;
            if (isWindows()) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            }
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Wait with timeout
            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                System.err.println("Command timeout after " + timeoutSeconds + " seconds");
                return null;
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            return output.toString();
        } catch (Exception e) {
            System.err.println("Error executing command with timeout: " + e.getMessage());
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
            // First check if kubectl is available without waiting too long
            if (!isKubectlInstalledQuick()) {
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                result.put("nodes", nodes);
                result.put("success", false);
                return result;
            }

            // Run kubectl get nodes with timeout
            String command = "kubectl get nodes -o wide";
            String output = executeCommandWithTimeout(command, 10); // 10 second timeout

            if (output == null || output.isEmpty()) {
                result.put("message", "No nodes found or Kubernetes cluster not accessible");
                result.put("nodes", nodes);
                result.put("success", false);
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
            System.err.println("Scan nodes error: " + e.getMessage());
            result.put("error", "Error scanning nodes: " + e.getMessage());
            result.put("nodes", nodes);
            result.put("success", false);
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
