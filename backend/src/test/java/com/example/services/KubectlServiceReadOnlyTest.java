package com.example.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link KubectlService} enforces its read-only verb allow-list:
 * a {@code get} command reaches process launch, while {@code delete}/{@code apply}
 * are refused before any process is started.
 *
 * <p>A subclass overrides {@link KubectlService#launch} so we can observe whether a
 * process <em>would</em> have been spawned, without depending on a real kubectl binary.
 */
class KubectlServiceReadOnlyTest {

    private static final String KC = "/tmp/kc.yaml";

    /** Records whether {@code launch} was reached; never actually starts a process. */
    private static class RecordingKubectlService extends KubectlService {
        boolean launched = false;

        RecordingKubectlService() {
            super(null); // repository is unused by the methods under test
        }

        @Override
        protected Process launch(ProcessBuilder pb) throws IOException {
            launched = true;
            // Stop here so no real kubectl process runs in the unit test.
            throw new IOException("stubbed-launch");
        }
    }

    @Test
    void getCommand_isAllowed_reachesProcessLaunch() {
        RecordingKubectlService svc = new RecordingKubectlService();
        List<String> cmd = List.of("kubectl", "--kubeconfig", KC, "get", "pods", "-A");

        assertTrue(svc.isReadOnlyCommand(cmd), "`get` must be recognized as read-only");

        KubectlService.ExecResult result = svc.executeWithResult(cmd, 5);

        assertTrue(svc.launched, "read-only `get` must pass the guard and reach process launch");
        // It failed only because launch is stubbed — not because it was refused.
        assertEquals("stubbed-launch", result.errorMessage);
    }

    @Test
    void deleteCommand_isRefused_withoutStartingProcess() {
        RecordingKubectlService svc = new RecordingKubectlService();
        List<String> cmd = List.of("kubectl", "--kubeconfig", KC, "delete", "pod", "nginx");

        assertFalse(svc.isReadOnlyCommand(cmd), "`delete` must not be read-only");

        KubectlService.ExecResult result = svc.executeWithResult(cmd, 5);

        assertFalse(svc.launched, "disallowed `delete` must NOT start a process");
        assertNull(result.exitCode, "refused command has no exit code");
        assertFalse(result.timedOut);
        assertNotNull(result.errorMessage);
        assertTrue(result.errorMessage.contains("delete"),
                "error should name the rejected verb: " + result.errorMessage);
    }

    @Test
    void applyCommand_isRefused_withoutStartingProcess() {
        RecordingKubectlService svc = new RecordingKubectlService();
        List<String> cmd = List.of("kubectl", "--kubeconfig", KC, "apply", "-f", "evil.yaml");

        assertFalse(svc.isReadOnlyCommand(cmd));

        // executeCommandWithTimeout returns null on refusal, and must not spawn a process.
        String out = svc.executeCommandWithTimeout(cmd, 5);

        assertFalse(svc.launched, "disallowed `apply` must NOT start a process");
        assertNull(out, "refused command returns null output");
    }

    @Test
    void verbExtraction_skipsKubeconfigAndContextPrefix() {
        KubectlService svc = new RecordingKubectlService();
        assertEquals("get", svc.extractKubectlVerb(
                List.of("kubectl", "--kubeconfig", KC, "--context", "prod", "get", "nodes")));
        assertEquals("auth", svc.extractKubectlVerb(
                List.of("kubectl", "--kubeconfig", KC, "auth", "can-i", "create", "pods", "-A")));
        assertEquals("cluster-info", svc.extractKubectlVerb(
                List.of("kubectl", "--kubeconfig", KC, "cluster-info")));
    }
}
