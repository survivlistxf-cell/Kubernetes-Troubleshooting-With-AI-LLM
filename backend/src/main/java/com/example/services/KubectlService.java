package com.example.services;

import com.example.entities.ClusterConfig;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class KubectlService {

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
            System.err.println("kubectl quick check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Build the kubectl command prefix for a specific cluster configuration.
     * Returns ["kubectl", "--kubeconfig", path] and optionally ["--context", ctx].
     */
    public List<String> buildKubectlPrefix(ClusterConfig cluster) {
        List<String> prefix = new ArrayList<>();
        prefix.add("kubectl");
        prefix.add("--kubeconfig");
        prefix.add(cluster.getKubeconfigPath());
        if (cluster.getContextName() != null && !cluster.getContextName().isBlank()) {
            prefix.add("--context");
            prefix.add(cluster.getContextName());
        }
        return prefix;
    }

    /**
     * Resolve the kubeconfig path for a given ClusterConfig, or fall back to the default.
     */
    public String resolveKubeconfigForCluster(ClusterConfig cluster) {
        if (cluster != null && cluster.getKubeconfigPath() != null && !cluster.getKubeconfigPath().isBlank()) {
            return cluster.getKubeconfigPath();
        }
        return resolveKubeconfigPath();
    }

    public String executeCommandWithTimeout(List<String> commandArgs, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(commandArgs);
            pb.redirectErrorStream(true);
            Process process = pb.start();

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
                System.err.println("Command timeout after " + timeoutSeconds + " seconds: " + commandArgs);
                return null;
            }

            readerThread.join(1000);
            return output.toString();
        } catch (Exception e) {
            System.err.println("Error executing command with timeout: " + e.getMessage());
            return null;
        }
    }

    public ExecResult executeWithResult(List<String> command, int timeoutSeconds) {
        if (command == null || command.isEmpty())
            return ExecResult.failed("empty command");
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
            pb.redirectErrorStream(true);
            Process process = pb.start();

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

        public static ExecResult skipped() {
            return new ExecResult(null, "", false, null);
        }

        public static ExecResult failed(String message) {
            return new ExecResult(null, null, false, message);
        }
    }
}
