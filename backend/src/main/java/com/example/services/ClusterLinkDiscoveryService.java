package com.example.services;

import com.example.entities.ClusterConfig;
import com.example.entities.ClusterLink;
import com.example.repositories.ClusterConfigRepository;
import com.example.repositories.ClusterLinkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Discovers cross-cluster connectivity by inspecting multi-cluster CRDs
 * on each configured cluster:
 *   - Submariner  → clusters.submariner.io
 *   - Linkerd     → links.multicluster.linkerd.io
 *
 * Detected links are reconciled with cluster_links: upserted when found,
 * deleted when they disappear. A 60-second in-memory cache avoids
 * hammering every API server on every topology refresh.
 */
@Service
public class ClusterLinkDiscoveryService {

    private static final long CACHE_TTL_MS = 60_000L;
    private static final String SOURCE_SUBMARINER = "submariner";
    private static final String SOURCE_LINKERD = "linkerd";

    private final KubectlService kubectl;
    private final ClusterConfigRepository clusterRepo;
    private final ClusterLinkRepository linkRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile long lastDiscoveryAt = 0L;
    private volatile List<Map<String, Object>> lastResult = null;

    public ClusterLinkDiscoveryService(KubectlService kubectl,
                                       ClusterConfigRepository clusterRepo,
                                       ClusterLinkRepository linkRepo) {
        this.kubectl = kubectl;
        this.clusterRepo = clusterRepo;
        this.linkRepo = linkRepo;
    }

    /**
     * Returns cached results when fresh (< 60 s), otherwise runs a full
     * discovery cycle and updates the DB before returning.
     */
    public synchronized List<Map<String, Object>> discoverOrCached(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && lastResult != null && (now - lastDiscoveryAt) < CACHE_TTL_MS) {
            return lastResult;
        }
        List<Map<String, Object>> result = runDiscovery();
        lastDiscoveryAt = System.currentTimeMillis();
        lastResult = result;
        return result;
    }

    // ----------------------------------------------------------------
    // Core discovery loop
    // ----------------------------------------------------------------

    private List<Map<String, Object>> runDiscovery() {
        List<ClusterConfig> clusters = clusterRepo.findByIsActiveTrueOrderByIsDefaultDescNameAsc();

        Map<String, ClusterConfig> byName = new HashMap<>();
        for (ClusterConfig c : clusters) {
            byName.put(c.getName().toLowerCase(), c);
        }

        Set<String> detectedKeys = new HashSet<>();   // "srcId:tgtId"
        List<Map<String, Object>> detected = new ArrayList<>();

        for (ClusterConfig src : clusters) {
            detectSubmariner(src, byName, detectedKeys, detected);
            detectLinkerd(src, byName, detectedKeys, detected);
        }

        reconcile(detectedKeys, detected);
        return detected;
    }

    // ----------------------------------------------------------------
    // Submariner: clusters.submariner.io
    // ----------------------------------------------------------------

    private void detectSubmariner(ClusterConfig src,
                                  Map<String, ClusterConfig> byName,
                                  Set<String> detectedKeys,
                                  List<Map<String, Object>> out) {
        List<String> cmd = new ArrayList<>(kubectl.buildKubectlPrefix(src));
        cmd.addAll(List.of("get", "clusters.submariner.io", "-A", "-o", "json", "--ignore-not-found"));

        KubectlService.ExecResult res = kubectl.executeWithResult(cmd, 10);
        if (res.exitCode == null || res.exitCode != 0 || isBlank(res.output)) return;

        try {
            Map<String, Object> json = objectMapper.readValue(res.output, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) json.get("items");
            if (items == null) return;

            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) item.get("metadata");
                if (meta == null) continue;
                String remoteName = (String) meta.get("name");
                if (isBlank(remoteName)) continue;

                addIfCorrelated(src, remoteName, byName, SOURCE_SUBMARINER, detectedKeys, out);
            }
        } catch (Exception ignored) {}
    }

    // ----------------------------------------------------------------
    // Linkerd multicluster: links.multicluster.linkerd.io
    // ----------------------------------------------------------------

    private void detectLinkerd(ClusterConfig src,
                                Map<String, ClusterConfig> byName,
                                Set<String> detectedKeys,
                                List<Map<String, Object>> out) {
        List<String> cmd = new ArrayList<>(kubectl.buildKubectlPrefix(src));
        cmd.addAll(List.of("get", "links.multicluster.linkerd.io", "-A", "-o", "json", "--ignore-not-found"));

        KubectlService.ExecResult res = kubectl.executeWithResult(cmd, 10);
        if (res.exitCode == null || res.exitCode != 0 || isBlank(res.output)) return;

        try {
            Map<String, Object> json = objectMapper.readValue(res.output, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) json.get("items");
            if (items == null) return;

            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> spec = (Map<String, Object>) item.get("spec");
                if (spec == null) continue;
                String remoteName = (String) spec.get("targetClusterName");
                if (isBlank(remoteName)) continue;

                addIfCorrelated(src, remoteName, byName, SOURCE_LINKERD, detectedKeys, out);
            }
        } catch (Exception ignored) {}
    }

    // ----------------------------------------------------------------
    // Correlation helper
    // ----------------------------------------------------------------

    private void addIfCorrelated(ClusterConfig src,
                                 String remoteName,
                                 Map<String, ClusterConfig> byName,
                                 String source,
                                 Set<String> detectedKeys,
                                 List<Map<String, Object>> out) {
        ClusterConfig tgt = byName.get(remoteName.toLowerCase());
        if (tgt == null || tgt.getId().equals(src.getId())) return;

        String key = src.getId() + ":" + tgt.getId();
        if (!detectedKeys.add(key)) return;

        out.add(Map.of(
                "sourceClusterId", src.getId(),
                "targetClusterId", tgt.getId(),
                "source", source,
                "linkType", "mesh"
        ));
    }

    // ----------------------------------------------------------------
    // DB reconciliation: upsert detected, delete stale
    // ----------------------------------------------------------------

    private void reconcile(Set<String> detectedKeys, List<Map<String, Object>> detected) {
        for (Map<String, Object> d : detected) {
            Long srcId = (Long) d.get("sourceClusterId");
            Long tgtId = (Long) d.get("targetClusterId");
            String source = (String) d.get("source");
            String linkType = (String) d.get("linkType");

            Optional<ClusterLink> existing = linkRepo.findBySourceClusterIdAndTargetClusterId(srcId, tgtId);
            ClusterLink link = existing.orElseGet(ClusterLink::new);
            link.setSourceClusterId(srcId);
            link.setTargetClusterId(tgtId);
            link.setSource(source);
            link.setLinkType(linkType);
            if (existing.isEmpty()) {
                link.setLastTestStatus(ClusterLink.STATUS_UNKNOWN);
            }
            linkRepo.save(link);
        }

        for (ClusterLink link : linkRepo.findAllByOrderByIdAsc()) {
            String key = link.getSourceClusterId() + ":" + link.getTargetClusterId();
            if (!detectedKeys.contains(key)) {
                linkRepo.delete(link);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
