package com.example.controllers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class NodeDetailsController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/node-details")
    public Map<String, Object> nodeDetails(
        @RequestParam(name = "name") String name,
        @RequestParam(name = "mode", required = false, defaultValue = "fast") String mode
    ) {
        Map<String, Object> result = new HashMap<>();

        if (name == null || name.isBlank()) {
            result.put("success", false);
            result.put("error", "Missing required query param: name");
            return result;
        }

        try {
            if (!isKubectlInstalledQuick()) {
                result.put("success", false);
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                return result;
            }

            // IMPORTANT: don't quote the node name; pass it as an argument.
            // Quoting ("docker-desktop") makes kubectl look for a literal name including quotes.
            List<String> describeCmd = List.of("kubectl", "describe", "node", name);
            List<String> jsonCmd = List.of("kubectl", "get", "node", name, "-o", "json");

            // Events for a node are usually involvedObject.kind=Node and involvedObject.name=<node>
            String fieldSelector = "involvedObject.kind=Node,involvedObject.name=" + name;
            List<String> eventsCmd = List.of(
                    "kubectl", "get", "events",
                    "--all-namespaces",
                    "--field-selector", fieldSelector,
                    "-o", "wide",
                    "--sort-by=.lastTimestamp"
            );

            String m = mode == null ? "fast" : mode.trim().toLowerCase();
            boolean full = m.equals("full") || m.equals("all") || m.equals("describe");

            // Keep these relatively small to avoid frontend proxy timeouts.
            // JSON is usually fast; describe/events can occasionally be slow on some clusters.
            // Docker Desktop / local clusters can be sluggish on the first kubectl call.
            // Keep these high enough to avoid false timeouts, but not too high to block the UI forever.
            int jsonTimeoutSec = 20;
            int describeTimeoutSec = 25;
            int eventsTimeoutSec = 20;

            // Always attempt JSON.
            ExecResult jsonRes = executeCommandWithTimeout(jsonCmd, jsonTimeoutSec);

            // Only run describe/events in full mode to avoid intermittent hangs.
            ExecResult describeRes = full ? executeCommandWithTimeout(describeCmd, describeTimeoutSec) : ExecResult.skipped();
            ExecResult eventsRes = full ? executeCommandWithTimeout(eventsCmd, eventsTimeoutSec) : ExecResult.skipped();

            Map<String, Object> nodeJson = null;
            if (jsonRes.output != null && !jsonRes.output.isBlank()) {
                try {
                    nodeJson = objectMapper.readValue(jsonRes.output, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception ignored) {
                }
            }

            result.put("success", true);
            result.put("name", name);
            result.put("describe", describeRes.output == null ? (describeRes.timedOut ? "(timed out)" : "") : describeRes.output);
            result.put("nodeJson", nodeJson != null ? nodeJson : (jsonRes.output == null ? (jsonRes.timedOut ? "(timed out)" : "") : jsonRes.output));
            result.put("events", eventsRes.output == null ? (eventsRes.timedOut ? "(timed out)" : "") : eventsRes.output);

            // Helpful diagnostics for the UI (so it can show partial data without failing hard)
            Map<String, Object> diag = new HashMap<>();
            diag.put("jsonTimeoutSec", jsonTimeoutSec);
            diag.put("describeTimeoutSec", describeTimeoutSec);
            diag.put("eventsTimeoutSec", eventsTimeoutSec);
            diag.put("describeTimedOut", full && describeRes.timedOut);
            diag.put("eventsTimedOut", full && eventsRes.timedOut);
            diag.put("jsonTimedOut", jsonRes.timedOut);
            diag.put("mode", full ? "full" : "fast");
            diag.put("jsonExitCode", jsonRes.exitCode);
            diag.put("describeExitCode", describeRes.exitCode);
            diag.put("eventsExitCode", eventsRes.exitCode);
            // Include stderr/stdout combined output already; expose a short error hint if empty.
            diag.put("jsonError", jsonRes.errorMessage);
            diag.put("describeError", describeRes.errorMessage);
            diag.put("eventsError", eventsRes.errorMessage);
            result.put("diagnostics", diag);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error fetching node details: " + e.getMessage());
            return result;
        }
    }

    private boolean isKubectlInstalledQuick() {
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
            return false;
        }
    }

    private ExecResult executeCommandWithTimeout(List<String> command, int timeoutSeconds) {
        if (command == null || command.isEmpty()) return ExecResult.failed("empty command");
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ExecResult.timedOut();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exit = process.exitValue();
            String out = output.toString();

            // If kubectl exits non-zero and produced no output, surface a helpful hint.
            String err = null;
            if (exit != 0 && (out == null || out.isBlank())) {
                err = "kubectl exited with code " + exit;
            }

            return ExecResult.completed(exit, out, err);
        } catch (Exception e) {
            return ExecResult.failed(e.getMessage());
        }
    }


    private static final class ExecResult {
        final Integer exitCode;
        final String output;
        final boolean timedOut;
        final String errorMessage;

        private ExecResult(Integer exitCode, String output, boolean timedOut, String errorMessage) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
            this.errorMessage = errorMessage;
        }

        static ExecResult completed(int exitCode, String output, String errorMessage) {
            return new ExecResult(exitCode, output, false, errorMessage);
        }

        static ExecResult timedOut() {
            return new ExecResult(null, null, true, "timeout");
        }

        static ExecResult skipped() {
            return new ExecResult(null, "", false, null);
        }

        static ExecResult failed(String message) {
            return new ExecResult(null, null, false, message);
        }
    }
}
