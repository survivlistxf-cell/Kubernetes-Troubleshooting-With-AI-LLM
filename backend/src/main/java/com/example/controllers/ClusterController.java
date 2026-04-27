package com.example.controllers;

import com.example.entities.ClusterConfig;
import com.example.repositories.ClusterConfigRepository;
import com.example.services.KubectlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/clusters")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ClusterController {

    private final ClusterConfigRepository clusterRepo;
    private final KubectlService kubectl;

    /** Directory where uploaded kubeconfig files are stored */
    private final Path kubeconfigStorageDir;

    public ClusterController(ClusterConfigRepository clusterRepo, KubectlService kubectl) {
        this.clusterRepo = clusterRepo;
        this.kubectl = kubectl;

        // Store kubeconfigs in ~/.kube/kubexplain/
        String userHome = System.getProperty("user.home");
        this.kubeconfigStorageDir = Paths.get(userHome, ".kube", "kubexplain");
        try {
            Files.createDirectories(this.kubeconfigStorageDir);
        } catch (IOException e) {
            System.err.println("Warning: could not create kubeconfig storage dir: " + e.getMessage());
        }
    }

    /** List all active clusters */
    @GetMapping
    public List<ClusterConfig> listClusters() {
        return clusterRepo.findByIsActiveTrueOrderByIsDefaultDescNameAsc();
    }

    /** Get a single cluster by ID */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCluster(@PathVariable Long id) {
        return clusterRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add a new cluster configuration.
     * Accepts multipart: kubeconfig file + metadata fields.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> addCluster(
            @RequestParam("kubeconfigFile") MultipartFile kubeconfigFile,
            @RequestParam("name") String name,
            @RequestParam(value = "displayName", required = false) String displayName,
            @RequestParam(value = "contextName", required = false) String contextName,
            @RequestParam(value = "defaultNamespace", required = false, defaultValue = "default") String defaultNamespace,
            @RequestParam(value = "isDefault", required = false, defaultValue = "false") boolean isDefault) {

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster name is required"));
        }

        if (kubeconfigFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Kubeconfig file is required"));
        }

        // Check for duplicate name
        if (clusterRepo.existsByName(name.trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "A cluster with name '" + name + "' already exists"));
        }

        try {
            // Save kubeconfig file to disk
            String safeFilename = name.trim().replaceAll("[^a-zA-Z0-9._-]", "_") + ".yaml";
            Path targetPath = kubeconfigStorageDir.resolve(safeFilename);
            kubeconfigFile.transferTo(targetPath.toFile());

            // If setting as default, clear other defaults
            if (isDefault) {
                clearDefaultFlags();
            }

            // Create entity
            ClusterConfig config = new ClusterConfig();
            config.setName(name.trim());
            config.setDisplayName(displayName != null && !displayName.isBlank() ? displayName.trim() : name.trim());
            config.setKubeconfigPath(targetPath.toAbsolutePath().toString());
            config.setContextName(contextName != null && !contextName.isBlank() ? contextName.trim() : null);
            config.setDefaultNamespace(defaultNamespace != null && !defaultNamespace.isBlank() ? defaultNamespace.trim() : "default");
            config.setDefault(isDefault);
            config.setActive(true);

            ClusterConfig saved = clusterRepo.save(config);

            return ResponseEntity.ok(Map.of(
                    "message", "Cluster added successfully",
                    "cluster", saved));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to save cluster: " + e.getMessage()));
        }
    }

    /** Update an existing cluster */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCluster(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        Optional<ClusterConfig> opt = clusterRepo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ClusterConfig config = opt.get();

        if (payload.containsKey("displayName")) {
            config.setDisplayName(String.valueOf(payload.get("displayName")));
        }
        if (payload.containsKey("contextName")) {
            Object val = payload.get("contextName");
            config.setContextName(val != null ? String.valueOf(val) : null);
        }
        if (payload.containsKey("defaultNamespace")) {
            config.setDefaultNamespace(String.valueOf(payload.get("defaultNamespace")));
        }
        if (payload.containsKey("isDefault")) {
            boolean val = Boolean.parseBoolean(String.valueOf(payload.get("isDefault")));
            if (val) clearDefaultFlags();
            config.setDefault(val);
        }
        if (payload.containsKey("isActive")) {
            config.setActive(Boolean.parseBoolean(String.valueOf(payload.get("isActive"))));
        }

        config.setUpdatedAt(LocalDateTime.now());
        clusterRepo.save(config);

        return ResponseEntity.ok(Map.of("message", "Cluster updated", "cluster", config));
    }

    /** Delete a cluster (removes file + DB entry) */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCluster(@PathVariable Long id) {
        Optional<ClusterConfig> opt = clusterRepo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ClusterConfig config = opt.get();

        // Only delete the kubeconfig file if it's inside our managed directory
        try {
            Path filePath = Paths.get(config.getKubeconfigPath());
            if (filePath.startsWith(kubeconfigStorageDir)) {
                Files.deleteIfExists(filePath);
            }
        } catch (Exception e) {
            System.err.println("Warning: could not delete kubeconfig file: " + e.getMessage());
        }

        clusterRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Cluster deleted"));
    }

    /** Test connectivity to a cluster */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testCluster(@PathVariable Long id) {
        Optional<ClusterConfig> opt = clusterRepo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ClusterConfig config = opt.get();

        try {
            List<String> cmd = new ArrayList<>(kubectl.buildKubectlPrefix(config));
            cmd.addAll(List.of("cluster-info"));

            KubectlService.ExecResult result = kubectl.executeWithResult(cmd, 10);

            boolean connected = result.exitCode != null && result.exitCode == 0;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("connected", connected);
            response.put("clusterId", config.getId());
            response.put("clusterName", config.getName());
            if (connected) {
                response.put("output", result.output);
            } else {
                response.put("error", result.errorMessage != null ? result.errorMessage : result.output);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "clusterId", config.getId(),
                    "error", e.getMessage()));
        }
    }

    /** List namespaces for a cluster */
    @GetMapping("/{id}/namespaces")
    public ResponseEntity<?> getNamespaces(@PathVariable Long id) {
        Optional<ClusterConfig> opt = clusterRepo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ClusterConfig config = opt.get();

        try {
            List<String> cmd = new ArrayList<>(kubectl.buildKubectlPrefix(config));
            cmd.addAll(List.of("get", "namespaces", "-o", "jsonpath={.items[*].metadata.name}"));

            KubectlService.ExecResult result = kubectl.executeWithResult(cmd, 10);

            if (result.exitCode != null && result.exitCode == 0 && result.output != null) {
                String[] namespaces = result.output.trim().split("\\s+");
                List<String> nsList = Arrays.stream(namespaces)
                        .filter(s -> !s.isBlank())
                        .sorted()
                        .toList();

                return ResponseEntity.ok(Map.of(
                        "namespaces", nsList,
                        "count", nsList.size(),
                        "clusterId", config.getId()));
            } else {
                return ResponseEntity.ok(Map.of(
                        "namespaces", List.of(),
                        "error", result.errorMessage != null ? result.errorMessage : "Could not list namespaces"));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "namespaces", List.of(),
                    "error", e.getMessage()));
        }
    }

    private void clearDefaultFlags() {
        clusterRepo.findByIsDefaultTrue().ifPresent(c -> {
            c.setDefault(false);
            clusterRepo.save(c);
        });
    }
}
