package com.example.services;

import com.example.entities.ClusterConfig;
import com.example.entities.ClusterLink;
import com.example.repositories.ClusterConfigRepository;
import com.example.repositories.ClusterLinkRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shallow-probes a detected ClusterLink by running kubectl cluster-info
 * against both the source and target cluster. Confirms both API servers
 * are reachable from the backend host.
 */
@Service
public class ClusterLinkTestService {

    private final KubectlService kubectl;
    private final ClusterConfigRepository clusterRepo;
    private final ClusterLinkRepository linkRepo;

    public ClusterLinkTestService(KubectlService kubectl,
                                  ClusterConfigRepository clusterRepo,
                                  ClusterLinkRepository linkRepo) {
        this.kubectl = kubectl;
        this.clusterRepo = clusterRepo;
        this.linkRepo = linkRepo;
    }

    public Map<String, Object> testLink(ClusterLink link) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("linkId", link.getId());

        Optional<ClusterConfig> srcOpt = clusterRepo.findById(link.getSourceClusterId());
        Optional<ClusterConfig> dstOpt = clusterRepo.findById(link.getTargetClusterId());

        if (srcOpt.isEmpty() || dstOpt.isEmpty()) {
            return persistResult(link, ClusterLink.STATUS_FAILED, "Source or target cluster missing", outcome);
        }

        ClusterConfig src = srcOpt.get();
        ClusterConfig dst = dstOpt.get();

        ProbeResult srcProbe = clusterInfoProbe(src);
        ProbeResult dstProbe = clusterInfoProbe(dst);

        outcome.put("source", probeAsMap(src, srcProbe));
        outcome.put("target", probeAsMap(dst, dstProbe));

        if (!srcProbe.ok || !dstProbe.ok) {
            String msg = "Shallow check failed: " +
                    (!srcProbe.ok ? "[source: " + srcProbe.message + "] " : "") +
                    (!dstProbe.ok ? "[target: " + dstProbe.message + "]" : "");
            return persistResult(link, ClusterLink.STATUS_FAILED, msg.trim(), outcome);
        }

        return persistResult(link, ClusterLink.STATUS_CONNECTED, "Both API servers reachable", outcome);
    }

    private ProbeResult clusterInfoProbe(ClusterConfig cluster) {
        try {
            List<String> cmd = new ArrayList<>(kubectl.buildKubectlPrefix(cluster));
            cmd.add("cluster-info");
            KubectlService.ExecResult r = kubectl.executeWithResult(cmd, 8);
            if (r.exitCode != null && r.exitCode == 0) {
                return ProbeResult.ok("API server reachable");
            }
            String msg = (r.errorMessage != null && !r.errorMessage.isBlank())
                    ? r.errorMessage
                    : (r.output != null ? truncate(r.output, 300) : "kubectl exited non-zero");
            return ProbeResult.fail(msg);
        } catch (Exception e) {
            return ProbeResult.fail(e.getMessage());
        }
    }

    private Map<String, Object> persistResult(ClusterLink link, String status,
                                              String message, Map<String, Object> outcome) {
        link.setLastTestStatus(status);
        link.setLastTestMessage(message);
        link.setLastTestAt(LocalDateTime.now());
        linkRepo.save(link);
        outcome.put("status", status);
        outcome.put("message", message);
        outcome.put("testedAt", link.getLastTestAt().toString());
        return outcome;
    }

    private Map<String, Object> probeAsMap(ClusterConfig c, ProbeResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clusterId", c.getId());
        m.put("clusterName", c.getName());
        m.put("ok", r.ok);
        m.put("message", r.message);
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static final class ProbeResult {
        final boolean ok;
        final String message;
        private ProbeResult(boolean ok, String message) { this.ok = ok; this.message = message; }
        static ProbeResult ok(String m) { return new ProbeResult(true, m); }
        static ProbeResult fail(String m) { return new ProbeResult(false, m == null ? "unknown error" : m); }
    }
}
