package com.example.controllers;

import com.example.services.KubectlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class PodsController {

    private final KubectlService kubectl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PodsController(KubectlService kubectl) {
        this.kubectl = kubectl;
    }

    @GetMapping("/scan-pods")
    public Map<String, Object> scanPods(
            @RequestParam(name = "namespace", required = false, defaultValue = "default") String namespace) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> pods = new ArrayList<>();

        try {
            if (!kubectl.isKubectlInstalledQuick()) {
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                result.put("pods", pods);
                result.put("success", false);
                return result;
            }

            String kubeconfigPath = kubectl.resolveKubeconfigPath();

            List<String> command = Arrays.asList(
                    "kubectl",
                    "--kubeconfig", kubeconfigPath,
                    "get", "pods",
                    "-n", namespace,
                    "-o", "wide");
            String output = kubectl.executeCommandWithTimeout(command, 10);

            if (output == null || output.isEmpty()) {
                result.put("message",
                        "No pods found in namespace '" + namespace + "' or Kubernetes cluster not accessible");
                result.put("pods", pods);
                result.put("success", false);
                return result;
            }

            // Parse kubectl output
            String[] lines = output.split("\n"); // Identifică fiecare rând
            boolean headerFound = false;

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty())
                    continue; // Sare peste liniile goale

                if (line.startsWith("NAME")) {
                    headerFound = true;
                    continue; // Nu procesăm header-ul ca fiind un pod, deci sărim la rândul următor
                }

                if (!headerFound)
                    continue;

                // Sparge linia in cuvinte (1 sau mai multe spatii goale)
                String[] parts = line.split("\\s+");
                if (parts.length < 3)
                    continue;

                Map<String, String> pod = new HashMap<>();
                pod.put("namespace", namespace);
                pod.put("name", parts[0]);
                pod.put("ready", parts.length > 1 ? parts[1] : "N/A");
                pod.put("status", parts.length > 2 ? parts[2] : "N/A");

                // restarts = 1 (5m ago), trebuie sarit
                int idx = 3;
                String restarts = parts.length > idx ? parts[idx] : "0";
                idx++;
                if (parts.length > idx && parts[idx].startsWith("(")) {
                    while (parts.length > idx && !parts[idx].endsWith(")")) {
                        idx++;
                    }
                    if (parts.length > idx) {
                        idx++;
                    }
                }
                pod.put("restarts", restarts);

                String rawAge = parts.length > idx ? parts[idx] : "N/A";
                pod.put("age", rawAge == null ? "N/A" : rawAge.replace("(", "").replace(")", ""));
                idx++;

                pod.put("ip", parts.length > idx ? parts[idx] : "N/A");
                idx++;
                pod.put("node", parts.length > idx ? parts[idx] : "N/A");
                pod.put("containers", parts.length > 1 ? parts[1].split("/")[1] : "N/A");
                pods.add(pod);
            }

            result.put("success", true);
            result.put("pods", pods);
            result.put("namespace", namespace);
            result.put("count", pods.size());
        } catch (Exception e) {
            System.err.println("Scan pods error: " + e.getMessage());
            result.put("error", "Error scanning pods: " + e.getMessage());
            result.put("pods", pods);
            result.put("success", false);
        }

        return result;
    }

    // La final, result =
    // {
    // "success": true,
    // "namespace": "default",
    // "count": 2,
    // "pods": [
    // {
    // "namespace": "default",
    // "name": "my-app-pod-6b7d",
    // "status": "Running",
    // "ready": "1/1",
    // "restarts": "0",
    // "age": "24h",
    // "ip": "10.244.0.5",
    // "node": "docker-desktop",
    // "containers": "1"
    // },
    // {
    // "namespace": "default",
    // "name": "db-pod-9c2e",
    // "status": "Error",
    // "ready": "0/1",
    // "restarts": "5",
    // "age": "12m",
    // "ip": "10.244.0.6",
    // "node": "docker-desktop",
    // "containers": "1"
    // }
    // ]
    // }

    @GetMapping("/pod-details")
    public Map<String, Object> podDetails(
            @RequestParam(name = "namespace", required = false, defaultValue = "default") String namespace,
            @RequestParam(name = "name") String name,
            @RequestParam(name = "type", required = false) String type) {
        Map<String, Object> result = new HashMap<>();

        if (name == null || name.isBlank()) {
            result.put("success", false);
            result.put("error", "Missing required query param: name");
            return result;
        }

        try {
            if (!kubectl.isKubectlInstalledQuick()) {
                result.put("success", false);
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                return result;
            }

            String kubeconfigPath = kubectl.resolveKubeconfigPath();
            result.put("success", true);
            result.put("namespace", namespace);
            result.put("name", name);

            // Fetch only requested type or everything if type is null (fallback)
            boolean fetchAll = (type == null || type.isBlank());

            if (fetchAll || "describe".equalsIgnoreCase(type)) {
                List<String> cmd = List.of("kubectl", "--kubeconfig", kubeconfigPath, "describe", "pod", name, "-n",
                        namespace);
                result.put("describe", kubectl.executeWithResult(cmd, 15).output);
            }

            if (fetchAll || "json".equalsIgnoreCase(type)) {
                List<String> cmd = List.of("kubectl", "--kubeconfig", kubeconfigPath, "get", "pod", name, "-n",
                        namespace, "-o", "json");
                String jsonOut = kubectl.executeWithResult(cmd, 15).output;
                try {
                    result.put("podJson", objectMapper.readValue(jsonOut, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (Exception ignored) {
                    result.put("podJson", jsonOut);
                }
            }

            if (fetchAll || "events".equalsIgnoreCase(type)) {
                String fieldSelector = "involvedObject.name=" + name;
                List<String> cmd = List.of("kubectl", "--kubeconfig", kubeconfigPath, "get", "events", "-n", namespace,
                        "--field-selector", fieldSelector, "-o", "wide");
                result.put("events", kubectl.executeWithResult(cmd, 15).output);
            }

            if (fetchAll || "logs".equalsIgnoreCase(type)) {
                List<String> cmd = List.of("kubectl", "--kubeconfig", kubeconfigPath, "logs", name, "-n", namespace,
                        "--tail=200");
                result.put("logs", kubectl.executeWithResult(cmd, 15).output);
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error fetching pod details: " + e.getMessage());
        }

        return result;
    }
}
