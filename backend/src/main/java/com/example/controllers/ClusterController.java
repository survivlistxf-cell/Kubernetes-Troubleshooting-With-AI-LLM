package com.example.controllers;

import com.example.entities.ClusterConfig;
import com.example.repositories.ClusterConfigRepository;
import com.example.services.KubeconfigPermissionChecker;
import com.example.services.KubectlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger logger = LoggerFactory.getLogger(ClusterController.class);

    /** Recommendation surfaced to the UI when a kubeconfig is over-privileged. */
    private static final String READONLY_SA_RECOMMENDATION =
            "Provide a kubeconfig bound to a read-only ServiceAccount (RBAC with get/list/watch only).";

    private final ClusterConfigRepository clusterRepo;
    private final KubectlService kubectl;
    private final KubeconfigPermissionChecker permissionChecker;

    /**
     * When true (default), adding a cluster whose kubeconfig can perform write
     * operations is rejected. When false, the cluster is still added but the API
     * response carries an explicit over-privileged warning.
     */
    @Value("${kubexplain.cluster.enforce-readonly-kubeconfig:false}")
    private boolean enforceReadOnly;

    /** Directory where uploaded kubeconfig files are stored */
    private final Path kubeconfigStorageDir;

    public ClusterController(ClusterConfigRepository clusterRepo,
                             KubectlService kubectl,
                             KubeconfigPermissionChecker permissionChecker) {
        this.clusterRepo = clusterRepo;
        this.kubectl = kubectl;
        this.permissionChecker = permissionChecker;

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

            String ctx = contextName != null && !contextName.isBlank() ? contextName.trim() : null;

            // Audit the kubeconfig: it must only grant read access. This runs
            // `auth can-i <write-verb> <resource> -A` (a safe, read-only probe).
            KubeconfigPermissionChecker.AuditResult audit =
                    permissionChecker.audit(targetPath.toAbsolutePath().toString(), ctx);

            if (!audit.readOnly() && enforceReadOnly) {
                // Over-privileged kubeconfig and enforcement is on: reject + clean up the file.
                try {
                    Files.deleteIfExists(targetPath);
                } catch (IOException cleanup) {
                    logger.warn("Could not delete rejected kubeconfig '{}': {}", targetPath, cleanup.getMessage());
                }
                logger.warn("Rejected cluster '{}': over-privileged kubeconfig allows {}",
                        name.trim(), audit.allowedWrites());

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "Kubeconfig is over-privileged: it can perform write operations.");
                body.put("readOnly", false);
                body.put("allowedWriteOperations", audit.allowedWrites());
                body.put("recommendation", READONLY_SA_RECOMMENDATION);
                return ResponseEntity.unprocessableEntity().body(body);
            }

            // If setting as default, clear other defaults
            if (isDefault) {
                clearDefaultFlags();
            }

            // Create entity
            ClusterConfig config = new ClusterConfig();
            config.setName(name.trim());
            config.setDisplayName(displayName != null && !displayName.isBlank() ? displayName.trim() : name.trim());
            config.setKubeconfigPath(targetPath.toAbsolutePath().toString());
            config.setContextName(ctx);
            config.setDefaultNamespace(defaultNamespace != null && !defaultNamespace.isBlank() ? defaultNamespace.trim() : "default");
            config.setDefault(isDefault);
            config.setActive(true);

            ClusterConfig saved = clusterRepo.save(config);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("cluster", saved);
            response.put("readOnly", audit.readOnly());
            if (audit.readOnly()) {
                logger.info("Cluster '{}' added; kubeconfig verified read-only", saved.getName());
                response.put("message", "Cluster added successfully (read-only kubeconfig verified)");
            } else {
                // enforceReadOnly == false: accepted with an explicit warning.
                logger.warn("Cluster '{}' added with OVER-PRIVILEGED kubeconfig (enforcement disabled); allows {}",
                        saved.getName(), audit.allowedWrites());
                response.put("message", "Cluster added, but the kubeconfig is over-privileged.");
                response.put("warning", "This kubeconfig can perform write operations on the cluster.");
                response.put("allowedWriteOperations", audit.allowedWrites());
                response.put("recommendation", READONLY_SA_RECOMMENDATION);
            }
            return ResponseEntity.ok(response);

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
