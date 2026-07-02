package com.example.services;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KubeconfigPermissionChecker}. The output of
 * {@code kubectl auth can-i} is faked via a {@link KubectlService} test double, so
 * no real cluster or kubectl binary is needed.
 *
 * <p>A hand-written subclass is used instead of a Mockito mock because the build's
 * bundled ByteBuddy cannot instrument newer JDKs; overriding {@code executeWithResult}
 * is just as expressive here and keeps the test toolchain-independent.
 */
class KubeconfigPermissionCheckerTest {

    private static final String KUBECONFIG = "/tmp/test-kubeconfig.yaml";

    /**
     * A {@link KubectlService} whose {@code executeWithResult} returns a canned
     * {@code auth can-i} answer. {@code yesWhen} decides which probes answer "yes".
     */
    private static class FakeKubectlService extends KubectlService {
        private final Predicate<List<String>> yesWhen;
        int execCalls = 0;

        FakeKubectlService(Predicate<List<String>> yesWhen) {
            super(null); // ClusterConfigRepository is unused by the methods we exercise
            this.yesWhen = yesWhen;
        }

        @Override
        public ExecResult executeWithResult(List<String> command, int timeoutSeconds) {
            execCalls++;
            return yesWhen.test(command)
                    ? ExecResult.completed(0, "yes\n", null)
                    : ExecResult.completed(1, "no\n", null);
        }
    }

    /**
     * Read-only kubeconfig: every {@code auth can-i} write probe answers "no",
     * so the audit must report read-only and an empty allowed-writes list.
     */
    @Test
    void allNo_isReadOnly() {
        FakeKubectlService kubectl = new FakeKubectlService(cmd -> false);

        KubeconfigPermissionChecker checker = new KubeconfigPermissionChecker(kubectl);
        KubeconfigPermissionChecker.AuditResult result = checker.audit(KUBECONFIG, null);

        assertTrue(result.readOnly(), "kubeconfig with all 'no' answers must be read-only");
        assertTrue(result.allowedWrites().isEmpty());
        assertEquals(KubeconfigPermissionChecker.WRITE_PROBES.size(), result.probes().size());
        // One exec per write probe.
        assertEquals(KubeconfigPermissionChecker.WRITE_PROBES.size(), kubectl.execCalls);
    }

    /**
     * Over-privileged kubeconfig: at least one probe answers "yes", so the audit
     * must report NOT read-only and list the allowed write operations.
     */
    @Test
    void anyYes_isOverPrivileged() {
        // "create pods" -> yes; every other probe -> no.
        FakeKubectlService kubectl = new FakeKubectlService(
                cmd -> cmd.contains("create") && cmd.contains("pods"));

        KubeconfigPermissionChecker checker = new KubeconfigPermissionChecker(kubectl);
        KubeconfigPermissionChecker.AuditResult result = checker.audit(KUBECONFIG, null);

        assertFalse(result.readOnly(), "a 'yes' answer must mark the kubeconfig over-privileged");
        assertEquals(List.of("create pods"), result.allowedWrites(),
                "allowed writes should list exactly the operation that returned yes");
    }

    /**
     * Sanity check: the probe command actually issued is the safe read-only
     * {@code auth can-i <verb> <resource> -A} form against the given kubeconfig.
     */
    @Test
    void probeCommandsAreReadOnlyAuthCanI() {
        List<List<String>> issued = new ArrayList<>();
        FakeKubectlService kubectl = new FakeKubectlService(cmd -> {
            issued.add(new ArrayList<>(cmd));
            return false;
        });

        new KubeconfigPermissionChecker(kubectl).audit(KUBECONFIG, "my-ctx");

        for (List<String> cmd : issued) {
            assertTrue(cmd.containsAll(List.of("kubectl", "--kubeconfig", KUBECONFIG)));
            assertTrue(cmd.containsAll(List.of("--context", "my-ctx")));
            assertTrue(cmd.containsAll(List.of("auth", "can-i", "-A")),
                    "probe must use the read-only `auth can-i ... -A` form: " + cmd);
        }
    }
}
