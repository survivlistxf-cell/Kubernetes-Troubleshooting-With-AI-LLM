package com.example.services;

import com.example.entities.ClusterConfig;
import com.example.repositories.ClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class KubectlService {

    private static final Logger logger = LoggerFactory.getLogger(KubectlService.class);

    /**
     * Allow-list of read-only kubectl verbs. Any command whose verb is not in this set
     * is refused before a process is started, so the gateway can only ever read from a
     * cluster.
     *
     * <p>The first six are the canonical read-only verbs. {@code logs}, {@code cluster-info}
     * and {@code auth} are also read-only and are already relied upon by existing features
     * (pod logs, cluster connectivity test, and the {@code auth can-i} kubeconfig audit),
     * so they are kept on the list to preserve current read behavior.
     */
    public static final Set<String> READ_ONLY_VERBS = Set.of(
            "get", "describe", "top", "version", "api-resources", "explain",
            "logs", "cluster-info", "auth"
    );

    private final ClusterConfigRepository clusterRepo;

    public KubectlService(ClusterConfigRepository clusterRepo) {
        this.clusterRepo = clusterRepo;
    }

    /**
     * Thrown when a kubectl command uses a verb that is not in {@link #READ_ONLY_VERBS}.
     * The process is never started.
     */
    public static class DisallowedKubectlCommandException extends RuntimeException {
        public DisallowedKubectlCommandException(String message) {
            super(message);
        }
    }

    /**
     * Extract the kubectl verb (the first non-flag token after the
     * {@code kubectl [--kubeconfig <path>] [--context <ctx>]} prefix).
     *
     * @return the verb, or {@code null} if none could be found
     */
    public String extractKubectlVerb(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        int i = 0;
        // Skip the kubectl binary token (may be an absolute path / have .exe suffix).
        String first = command.get(0);
        if (first != null) {
            String normalized = first.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (name.equals("kubectl") || name.equals("kubectl.exe")) {
                i = 1;
            }
        }
        for (; i < command.size(); i++) {
            String token = command.get(i);
            if (token == null) {
                continue;
            }
            // Prefix flags that consume the following token.
            if (token.equals("--kubeconfig") || token.equals("--context")) {
                i++; // skip the flag's value as well
                continue;
            }
            if (token.startsWith("-")) {
                continue; // any other leading global flag
            }
            return token; // first bare token is the verb
        }
        return null;
    }

    /** @return true when the command's verb is in the read-only allow-list. */
    public boolean isReadOnlyCommand(List<String> command) {
        String verb = extractKubectlVerb(command);
        return verb != null && READ_ONLY_VERBS.contains(verb.toLowerCase());
    }

    /**
     * Guard executed before any process is started. Logs and throws when the command's
     * verb is not read-only.
     */
    private void ensureReadOnly(List<String> command) {
        if (!isReadOnlyCommand(command)) {
            String verb = extractKubectlVerb(command);
            logger.warn("Refused non-read-only kubectl command (verb='{}', allowed={}): {}",
                    verb, READ_ONLY_VERBS, command);
            throw new DisallowedKubectlCommandException(
                    "kubectl verb '" + verb + "' is not allowed; only read-only verbs "
                    + READ_ONLY_VERBS + " may be executed");
        }
    }

    /**
     * Single choke-point that actually spawns the OS process. Centralized (and
     * {@code protected}) so the read-only guard always runs first and so tests can
     * observe whether a process would have been started.
     */
    protected Process launch(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    /**
     * Build the kubectl prefix args for a cluster id, falling back to a generic
     * {@code kubectl --kubeconfig <path>} when the id is null or unknown.
     *
     * <p>This single helper replaces the duplicated private {@code kubectlBase}
     * methods that previously lived in {@link com.example.controllers.PodsController}
     * and {@link com.example.controllers.NodesController}.
     */
    public List<String> kubectlBase(Long clusterId) {
        if (clusterId != null) {
            Optional<ClusterConfig> opt = clusterRepo.findById(clusterId);
            if (opt.isPresent()) {
                return buildKubectlPrefix(opt.get());
            }
        }
        return new ArrayList<>(Arrays.asList("kubectl", "--kubeconfig", resolveKubeconfigPath()));
    }

    public boolean isKubectlInstalledQuick() {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "version", "--client", "-o", "json");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (Exception e) {
            logger.warn("kubectl quick check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Build the kubectl command prefix for a specific cluster configuration.
     * Returns ["kubectl", "--kubeconfig", path] and optionally ["--context", ctx].
     */
    public List<String> buildKubectlPrefix(ClusterConfig cluster) {
        return buildKubectlPrefix(cluster.getKubeconfigPath(), cluster.getContextName());
    }

    /**
     * Build the kubectl command prefix from a raw kubeconfig path + optional context.
     * Useful before a {@link ClusterConfig} is persisted (e.g. when auditing an
     * uploaded kubeconfig at add-cluster time).
     */
    public List<String> buildKubectlPrefix(String kubeconfigPath, String contextName) {
        List<String> prefix = new ArrayList<>();
        prefix.add("kubectl");
        prefix.add("--kubeconfig");
        prefix.add(kubeconfigPath);
        if (contextName != null && !contextName.isBlank()) {
            prefix.add("--context");
            prefix.add(contextName);
        }
        return prefix;
    }

    public String executeCommandWithTimeout(List<String> commandArgs, int timeoutSeconds) {
        try {
            ensureReadOnly(commandArgs); // refuse non-read-only verbs before spawning
            ProcessBuilder pb = new ProcessBuilder(commandArgs);
            pb.redirectErrorStream(true);
            Process process = launch(pb);

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception readErr) {
                    // Best-effort: the stream may close abruptly when the process is killed
                    // on timeout, but at DEBUG the cause is still traceable.
                    logger.debug("kubectl output reader stopped: {}", readErr.getMessage());
                }
            });
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                logger.error("Command timeout after {} seconds: {}", timeoutSeconds, commandArgs);
                return null;
            }

            readerThread.join(1000);
            return output.toString();
        } catch (Exception e) {
            logger.error("Error executing command with timeout: {}", e.getMessage(), e);
            return null;
        }
    }

    public ExecResult executeWithResult(List<String> command, int timeoutSeconds) {
        if (command == null || command.isEmpty())
            return ExecResult.failed("empty command");
        try {
            ensureReadOnly(command); // refuse non-read-only verbs before spawning
        } catch (DisallowedKubectlCommandException e) {
            return ExecResult.failed(e.getMessage());
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
            pb.redirectErrorStream(true);
            Process process = launch(pb);

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                }
            });
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                return ExecResult.timedOut();
            }

            readerThread.join(1000);
            int exit = process.exitValue();
            String out = output.toString();

            String err = null;
            if (exit != 0 && (out == null || out.isBlank())) {
                err = "kubectl exited with code " + exit;
            }

            return ExecResult.completed(exit, out, err);
        } catch (Exception e) {
            return ExecResult.failed(e.getMessage());
        }
    }

    public String resolveKubeconfigPath() {
        String envPath = Optional.ofNullable(System.getenv("KUBECONFIG"))
                .filter(p -> !p.trim().isEmpty())
                .orElse(null);

        if (envPath != null) {
            return envPath;
        }

        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".kube", "licenta-cluster.yaml").toString();
    }

    public static final class ExecResult {
        public final Integer exitCode;
        public final String output;
        public final boolean timedOut;
        public final String errorMessage;

        private ExecResult(Integer exitCode, String output, boolean timedOut, String errorMessage) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
            this.errorMessage = errorMessage;
        }

        public static ExecResult completed(int exitCode, String output, String errorMessage) {
            return new ExecResult(exitCode, output, false, errorMessage);
        }

        public static ExecResult timedOut() {
            return new ExecResult(null, null, true, "timeout");
        }

        public static ExecResult failed(String message) {
            return new ExecResult(null, null, false, message);
        }
    }
}
