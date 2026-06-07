package com.example.controllers;

import com.example.entities.ClusterConfig;
import com.example.entities.ClusterLink;
import com.example.repositories.ClusterConfigRepository;
import com.example.repositories.ClusterLinkRepository;
import com.example.services.ClusterLinkDiscoveryService;
import com.example.services.KubectlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Aggregated topology view: clusters + auto-detected links + lightweight
 * pod/node counts per cluster. Counts are cached for 30 seconds so opening
 * the Topology tab repeatedly does not hammer every API server.
 */
@RestController
@RequestMapping("/api/clusters")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class TopologyController {

    private static final long COUNT_CACHE_TTL_MS = 30_000L;

    private final ClusterConfigRepository clusterRepo;
    private final ClusterLinkRepository linkRepo;
    private final KubectlService kubectl;
    private final ClusterLinkDiscoveryService discoveryService;

    private final Map<Long, CountSnapshot> countCache = new ConcurrentHashMap<>();

    public TopologyController(ClusterConfigRepository clusterRepo,
                              ClusterLinkRepository linkRepo,
                              KubectlService kubectl,
                              ClusterLinkDiscoveryService discoveryService) {
        this.clusterRepo = clusterRepo;
        this.linkRepo = linkRepo;
        this.kubectl = kubectl;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/topology")
    public ResponseEntity<?> topology(
            @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh) {

        List<ClusterConfig> clusters = clusterRepo.findByIsActiveTrueOrderByIsDefaultDescNameAsc();
        List<ClusterLink> links = linkRepo.findAllByOrderByIdAsc();

        Map<Long, CountSnapshot> snapshots = new LinkedHashMap<>();
        if (!clusters.isEmpty()) {
            List<CompletableFuture<Map.Entry<Long, CountSnapshot>>> futures = new ArrayList<>();
            for (ClusterConfig c : clusters) {
                futures.add(CompletableFuture.supplyAsync(() ->
                        Map.entry(c.getId(), getOrComputeSnapshot(c, refresh))));
            }
            for (CompletableFuture<Map.Entry<Long, CountSnapshot>> f : futures) {
                try {
                    Map.Entry<Long, CountSnapshot> e = f.get(20, TimeUnit.SECONDS);
                    snapshots.put(e.getKey(), e.getValue());
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    // skip cluster on timeout
                }
            }
        }

        List<Map<String, Object>> clusterPayload = new ArrayList<>();
        for (ClusterConfig c : clusters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("displayName", c.getDisplayName());
            m.put("defaultNamespace", c.getDefaultNamespace());
            m.put("isDefault", c.isDefault());
            m.put("isActive", c.isActive());

            CountSnapshot snap = snapshots.get(c.getId());
            if (snap != null) {
                m.put("status", snap.status);
                m.put("podCount", snap.podCount);
                m.put("nodeCount", snap.nodeCount);
                m.put("countsAt", snap.computedAt);
            } else {
                m.put("status", "unknown");
            }
            clusterPayload.add(m);
        }

        List<Map<String, Object>> linkPayload = new ArrayList<>();
        for (ClusterLink l : links) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("sourceId", l.getSourceClusterId());
            m.put("targetId", l.getTargetClusterId());
            m.put("type", l.getLinkType());
            m.put("source", l.getSource());
            m.put("lastTestStatus", l.getLastTestStatus());
            m.put("lastTestAt", l.getLastTestAt());
            m.put("lastTestMessage", l.getLastTestMessage());
            linkPayload.add(m);
        }

        return ResponseEntity.ok(Map.of(
                "clusters", clusterPayload,
                "links", linkPayload
        ));
    }

    /** Trigger CRD-based link discovery. The service caches results for 60 s. */
    @PostMapping("/discover-links")
    public ResponseEntity<?> discoverLinks(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        List<Map<String, Object>> detected = discoveryService.discoverOrCached(force);
        return ResponseEntity.ok(Map.of(
                "detected", detected,
                "count", detected.size()
        ));
    }

    // ----------------------------------------------------------------
    // Count snapshots
    // ----------------------------------------------------------------

    private CountSnapshot getOrComputeSnapshot(ClusterConfig cluster, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        CountSnapshot cached = countCache.get(cluster.getId());
        if (!forceRefresh && cached != null && (now - cached.timestamp) < COUNT_CACHE_TTL_MS) {
            return cached;
        }
        CountSnapshot fresh = computeSnapshot(cluster);
        countCache.put(cluster.getId(), fresh);
        return fresh;
    }

    private CountSnapshot computeSnapshot(ClusterConfig cluster) {
        CountSnapshot s = new CountSnapshot();
        s.timestamp = System.currentTimeMillis();
        s.computedAt = java.time.LocalDateTime.now().toString();

        try {
            List<String> nodeCmd = new ArrayList<>(kubectl.buildKubectlPrefix(cluster));
            nodeCmd.addAll(List.of("get", "nodes", "--no-headers"));
            KubectlService.ExecResult nodeRes = kubectl.executeWithResult(nodeCmd, 8);
            if (nodeRes.exitCode != null && nodeRes.exitCode == 0) {
                s.nodeCount = countNonEmptyLines(nodeRes.output);
                s.status = "connected";
            } else {
                s.status = "failed";
                return s;
            }

            List<String> podCmd = new ArrayList<>(kubectl.buildKubectlPrefix(cluster));
            podCmd.addAll(List.of("get", "pods", "--all-namespaces", "--no-headers"));
            KubectlService.ExecResult podRes = kubectl.executeWithResult(podCmd, 12);
            if (podRes.exitCode != null && podRes.exitCode == 0) {
                s.podCount = countNonEmptyLines(podRes.output);
            }
        } catch (Exception e) {
            s.status = "failed";
        }
        return s;
    }

    private int countNonEmptyLines(String s) {
        if (s == null || s.isBlank()) return 0;
        int c = 0;
        for (String line : s.split("\n")) {
            if (!line.trim().isEmpty()) c++;
        }
        return c;
    }

    private static final class CountSnapshot {
        String status = "unknown";
        int podCount = 0;
        int nodeCount = 0;
        long timestamp;
        String computedAt;
    }
}
