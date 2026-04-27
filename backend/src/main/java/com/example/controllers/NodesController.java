package com.example.controllers;

import com.example.entities.ClusterConfig;
import com.example.repositories.ClusterConfigRepository;
import com.example.services.KubectlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class NodesController {

    private final KubectlService kubectl;
    private final ClusterConfigRepository clusterRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NodesController(KubectlService kubectl, ClusterConfigRepository clusterRepo) {
        this.kubectl = kubectl;
        this.clusterRepo = clusterRepo;
    }

    /** Build kubectl prefix args for a cluster, or default */
    private List<String> kubectlBase(Long clusterId) {
        if (clusterId != null) {
            Optional<ClusterConfig> opt = clusterRepo.findById(clusterId);
            if (opt.isPresent()) {
                return kubectl.buildKubectlPrefix(opt.get());
            }
        }
        return new ArrayList<>(Arrays.asList("kubectl", "--kubeconfig", kubectl.resolveKubeconfigPath()));
    }

    @GetMapping("/scan-nodes")
    public Map<String, Object> scanNodes(
            @RequestParam(name = "clusterId", required = false) Long clusterId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> nodes = new ArrayList<>();

        try {
            if (!kubectl.isKubectlInstalledQuick()) {
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                result.put("nodes", nodes);
                result.put("success", false);
                return result;
            }

            List<String> command = new ArrayList<>(kubectlBase(clusterId));
            command.addAll(Arrays.asList("get", "nodes", "-o", "wide"));
            String output = kubectl.executeCommandWithTimeout(command, 10);

            if (output == null || output.isEmpty()) {
                result.put("message", "No nodes found or Kubernetes cluster not accessible");
                result.put("nodes", nodes);
                result.put("success", false);
                return result;
            }

            String[] lines = output.split("\n");
            boolean headerFound = false;

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty())
                    continue;

                if (line.startsWith("NAME")) {
                    headerFound = true;
                    continue;
                }

                if (!headerFound)
                    continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 5)
                    continue;

                String statusCol = parts[1];
                if (!statusCol.equalsIgnoreCase("Ready") &&
                        !statusCol.equalsIgnoreCase("NotReady") &&
                        !statusCol.contains("Ready")) {
                    continue;
                }

                Map<String, String> node = new HashMap<>();
                node.put("name", parts[0]);
                node.put("status", parts[1]);
                node.put("roles", parts.length > 2 ? parts[2] : "N/A");
                node.put("age", parts.length > 3 ? parts[3] : "N/A");
                node.put("version", parts.length > 4 ? parts[4] : "N/A");
                node.put("internalIp", parts.length > 5 ? parts[5] : "N/A");
                node.put("externalIp", parts.length > 6 ? parts[6] : "N/A");
                nodes.add(node);
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

    @GetMapping("/node-details")
    public Map<String, Object> nodeDetails(
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
            result.put("name", name);

            boolean fetchAll = (type == null || type.isBlank());

            if (fetchAll || "describe".equalsIgnoreCase(type)) {
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("describe", "node", name));
                result.put("describe", kubectl.executeWithResult(cmd, 25).output);
            }

            if (fetchAll || "json".equalsIgnoreCase(type)) {
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("get", "node", name, "-o", "json"));
                String jsonOut = kubectl.executeWithResult(cmd, 20).output;
                try {
                    result.put("nodeJson", objectMapper.readValue(jsonOut, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (Exception ignored) {
                    result.put("nodeJson", jsonOut);
                }
            }

            if (fetchAll || "events".equalsIgnoreCase(type)) {
                String fieldSelector = "involvedObject.kind=Node,involvedObject.name=" + name;
                List<String> cmd = new ArrayList<>(kubectlBase(clusterId));
                cmd.addAll(List.of("get", "events",
                        "--all-namespaces",
                        "--field-selector", fieldSelector,
                        "-o", "wide",
                        "--sort-by=.lastTimestamp"));
                result.put("events", kubectl.executeWithResult(cmd, 20).output);
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error fetching node details: " + e.getMessage());
        }

        return result;
    }
}
