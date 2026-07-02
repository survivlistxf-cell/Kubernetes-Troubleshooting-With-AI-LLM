package com.example.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Audits an uploaded kubeconfig to verify it only grants read access.
 *
 * <p>For a set of representative <em>write</em> verbs/resources it runs
 * {@code kubectl --kubeconfig <path> auth can-i <verb> <resource> -A}. The
 * {@code auth can-i} call is itself a read-only operation (it issues a
 * {@code SelfSubjectAccessReview}), so probing the cluster this way is safe and
 * never mutates anything.
 *
 * <p>If any probe answers {@code yes}, the kubeconfig is over-privileged. If all
 * answer {@code no}, the cluster is accepted as read-only.
 */
@Service
public class KubeconfigPermissionChecker {

    private static final Logger logger = LoggerFactory.getLogger(KubeconfigPermissionChecker.class);

    /** Seconds to wait for each {@code auth can-i} probe. */
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    /** Representative write operations a read-only kubeconfig must NOT be allowed to do. */
    static final List<WriteProbe> WRITE_PROBES = List.of(
            new WriteProbe("create", "pods"),
            new WriteProbe("delete", "pods"),
            new WriteProbe("patch",  "deployments"),
            new WriteProbe("create", "secrets")
    );

    private final KubectlService kubectl;

    public KubeconfigPermissionChecker(KubectlService kubectl) {
        this.kubectl = kubectl;
    }

    /**
     * Run all write-permission probes against the kubeconfig at {@code kubeconfigPath}.
     *
     * @param kubeconfigPath absolute path to the kubeconfig file
     * @param contextName    optional context inside a multi-context kubeconfig (may be null)
     * @return an {@link AuditResult} describing whether the kubeconfig is read-only
     */
    public AuditResult audit(String kubeconfigPath, String contextName) {
        List<ProbeResult> results = new ArrayList<>(WRITE_PROBES.size());

        for (WriteProbe probe : WRITE_PROBES) {
            List<String> cmd = new ArrayList<>(kubectl.buildKubectlPrefix(kubeconfigPath, contextName));
            cmd.add("auth");
            cmd.add("can-i");
            cmd.add(probe.verb());
            cmd.add(probe.resource());
            cmd.add("-A"); // --all-namespaces

            KubectlService.ExecResult exec = kubectl.executeWithResult(cmd, PROBE_TIMEOUT_SECONDS);
            boolean allowed = isYes(exec);
            results.add(new ProbeResult(probe.verb(), probe.resource(), allowed));

            logger.info("kubeconfig audit: can-i {} {} -A -> {}",
                    probe.verb(), probe.resource(), allowed ? "yes" : "no");
        }

        AuditResult result = new AuditResult(results);
        if (result.readOnly()) {
            logger.info("kubeconfig audit: PASSED — kubeconfig is read-only (no write verbs allowed)");
        } else {
            logger.warn("kubeconfig audit: OVER-PRIVILEGED — allowed write operations: {}",
                    result.allowedWrites());
        }
        return result;
    }

    /**
     * Interprets the output of {@code kubectl auth can-i}. The command prints a single
     * {@code yes}/{@code no} token (and sets the exit code accordingly). We treat the
     * last non-blank line equal to {@code yes} as "allowed". Anything else — including
     * errors or an unreachable cluster — is treated as not allowed.
     */
    private static boolean isYes(KubectlService.ExecResult exec) {
        if (exec == null || exec.output == null) {
            return false;
        }
        String lastLine = "";
        for (String line : exec.output.split("\\R")) {
            if (!line.isBlank()) {
                lastLine = line.trim();
            }
        }
        return lastLine.equalsIgnoreCase("yes");
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** A single (verb, resource) write operation to probe. */
    record WriteProbe(String verb, String resource) {}

    /** Result of one probe. */
    public record ProbeResult(String verb, String resource, boolean allowed) {
        public String operation() {
            return verb + " " + resource;
        }
    }

    /** Aggregate audit result over all probes. */
    public record AuditResult(List<ProbeResult> probes) {

        /** True when no write probe was allowed. */
        public boolean readOnly() {
            return probes.stream().noneMatch(ProbeResult::allowed);
        }

        /** The write operations the kubeconfig is (wrongly) allowed to perform. */
        public List<String> allowedWrites() {
            return probes.stream()
                    .filter(ProbeResult::allowed)
                    .map(ProbeResult::operation)
                    .toList();
        }
    }
}
