package com.example.controllers;

import com.example.entities.ClusterConfig;
import com.example.repositories.ClusterConfigRepository;
import com.example.services.KubectlService;
import com.example.utils.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api")
public class PodsController {

    private static final Logger logger = LoggerFactory.getLogger(PodsController.class);

    private final KubectlService kubectl;
    private final ClusterConfigRepository clusterRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PodsController(KubectlService kubectl, ClusterConfigRepository clusterRepo) {
        this.kubectl = kubectl;
        this.clusterRepo = clusterRepo;
    }

    /** Wrapper kept for call-site readability — delegates to {@link KubectlService#kubectlBase(Long)}. */
    private List<String> kubectlBase(Long clusterId) {
        return kubectl.kubectlBase(clusterId);
    }

    @GetMapping("/scan-pods")
    public Map<String, Object> scanPods(
            @RequestParam(name = "namespace", required = false, defaultValue = "default") String namespace,
            @RequestParam(name = "clusterId", required = false) Long clusterId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> pods = new ArrayList<>();

        try {
            if (!kubectl.isKubectlInstalledQuick()) {
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                result.put("pods", pods);
                result.put("success", false);
                return result;
            }

            List<String> command = new ArrayList<>(kubectlBase(clusterId));
            command.addAll(Arrays.asList("get", "pods", "-n", namespace, "-o", "wide"));
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
            logger.error("Scan pods error: {}", e.getMessage(), e);
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

    /** Run scan-pods on multiple clusters in parallel. clusterIds: CSV, max {@link Utils#MAX_MULTI_CLUSTERS}. */
    @GetMapping("/scan-pods/multi")
    public ResponseEntity<?> scanPodsMulti(
            @RequestParam(name = "clusterIds") String clusterIds,
            @RequestParam(name = "namespace", required = false, defaultValue = "default") String namespace,
            @RequestParam(name = "namespaceMap", required = false) String namespaceMapJson) {

        List<Long> ids = Utils.parseClusterIds(clusterIds);
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "clusterIds is required"));
        }
        if (ids.size() > Utils.MAX_MULTI_CLUSTERS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Maximum " + Utils.MAX_MULTI_CLUSTERS + " clusters allowed per multi-scan"));
        }

        Map<Long, String> namespaceMap = parseNamespaceMap(namespaceMapJson);
        if (namespaceMapJson != null && !namespaceMapJson.isBlank() && namespaceMap == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "namespaceMap must be valid JSON"));
        }

        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (Long id : ids) {
            futures.add(CompletableFuture.supplyAsync(() -> scanPodsForCluster(id, namespace, namespaceMap)));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(20, TimeUnit.SECONDS));
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                results.add(Map.of(
                        "clusterId", ids.get(i),
                        "success", false,
                        "error", "Scan timed out or failed: " + e.getMessage(),
                        "pods", List.of()));
                Thread.currentThread().interrupt();
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("namespace", namespace);
        if (namespaceMap != null && !namespaceMap.isEmpty()) {
            response.put("namespaceMap", namespaceMap);
        }
        return ResponseEntity.ok(response);
    }

    private Map<Long, String> parseNamespaceMap(String namespaceMapJson) {
        if (namespaceMapJson == null || namespaceMapJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> raw = objectMapper.readValue(namespaceMapJson, new TypeReference<Map<String, String>>() {
            });
            Map<Long, String> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                try {
                    Long clusterId = Long.parseLong(entry.getKey().trim());
                    String namespace = entry.getValue().trim();
                    if (!namespace.isBlank()) {
                        parsed.put(clusterId, namespace);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveNamespaceForCluster(Long clusterId, String globalNamespace, Map<Long, String> namespaceMap) {
        String namespace = namespaceMap != null ? namespaceMap.get(clusterId) : null;
        if (namespace != null && !namespace.isBlank()) {
            return namespace.trim();
        }

        if (globalNamespace != null && !globalNamespace.isBlank()) {
            return globalNamespace.trim();
        }

        if (clusterId != null) {
            Optional<ClusterConfig> opt = clusterRepo.findById(clusterId);
            if (opt.isPresent() && opt.get().getDefaultNamespace() != null && !opt.get().getDefaultNamespace().isBlank()) {
                return opt.get().getDefaultNamespace().trim();
            }
        }

        return "default";
    }

    private Map<String, Object> scanPodsForCluster(Long clusterId, String namespace, Map<Long, String> namespaceMap) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("clusterId", clusterId);
        if (clusterId == null) {
            entry.put("success", false);
            entry.put("error", "Cluster not found");
            entry.put("pods", List.of());
            return entry;
        }

        Optional<ClusterConfig> opt = clusterRepo.findById(clusterId);
        if (opt.isEmpty()) {
            entry.put("success", false);
            entry.put("error", "Cluster not found");
            entry.put("pods", List.of());
            return entry;
        }
        entry.put("clusterName", opt.get().getName());
        entry.put("clusterDisplayName", opt.get().getDisplayName());
        String resolvedNamespace = resolveNamespaceForCluster(clusterId, namespace, namespaceMap);
        Map<String, Object> scan = scanPods(resolvedNamespace, clusterId);
        entry.putAll(scan);
        entry.put("namespace", resolvedNamespace);
        return entry;
    }

    @GetMapping("/pod-details")
    public Map<String, Object> podDetails(
            @RequestParam(name = "namespace", required = false, defaultValue = "default") String namespace,
            @RequestParam(name = "name") String name,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "clusterId", required = false) Long clusterId) {
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

            result.put("success", true);
            result.put("namespace", namespace);
            result.put("name", name);

            // Fetch only requested type or everything if type is null (fallback)
            boolean fetchAll = (type == null || type.isBlank());

            if (fetchAll || "describe".equalsIgnoreCase(type)) {
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("describe", "pod", name, "-n", namespace));
                result.put("describe", kubectl.executeWithResult(cmd, 15).output);
            }

            if (fetchAll || "json".equalsIgnoreCase(type)) {
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("get", "pod", name, "-n", namespace, "-o", "json"));
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
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("get", "events", "-n", namespace, "--field-selector", fieldSelector, "-o", "wide"));
                result.put("events", kubectl.executeWithResult(cmd, 15).output);
            }

            if (fetchAll || "logs".equalsIgnoreCase(type)) {
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("logs", name, "-n", namespace, "--tail=200"));
                result.put("logs", kubectl.executeWithResult(cmd, 15).output);
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error fetching pod details: " + e.getMessage());
        }

        return result;
    }
}
