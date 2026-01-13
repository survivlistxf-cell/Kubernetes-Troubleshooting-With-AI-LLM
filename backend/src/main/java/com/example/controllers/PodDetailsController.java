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
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class PodDetailsController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/pod-details")
    public Map<String, Object> podDetails(
            @RequestParam(name = "namespace") String namespace,
            @RequestParam(name = "name") String name,
            @RequestParam(name = "container", required = false) String container,
            @RequestParam(name = "tailLines", required = false, defaultValue = "200") int tailLines
    ) {
        Map<String, Object> result = new HashMap<>();

        if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) {
            result.put("success", false);
            result.put("error", "Missing required query params: namespace, name");
            return result;
        }

        try {
            if (!isKubectlInstalledQuick()) {
                result.put("success", false);
                result.put("error", "kubectl not found - Kubernetes may not be configured");
                return result;
            }

            String nsArg = escapeShellArg(namespace);
            String nameArg = escapeShellArg(name);

            String describeCmd = "kubectl describe pod " + nameArg + " -n " + nsArg;
            String jsonCmd = "kubectl get pod " + nameArg + " -n " + nsArg + " -o json";
            // Events: easiest is field-selector on involvedObject
            String eventsCmd = "kubectl get events -n " + nsArg + " --field-selector involvedObject.name=" + escapeShellBareArg(name) + " -o wide --sort-by=.lastTimestamp";

            String describeOut = executeCommandWithTimeout(describeCmd, 12);
            String jsonOut = executeCommandWithTimeout(jsonCmd, 12);
            String eventsOut = executeCommandWithTimeout(eventsCmd, 12);

            Map<String, Object> podJson = null;
            if (jsonOut != null && !jsonOut.isBlank()) {
                try {
                    podJson = objectMapper.readValue(jsonOut, new TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {
                }
            }

            // Logs (optional container)
            String logsOut = null;
            try {
                int safeTail = Math.max(10, Math.min(2000, tailLines));
                String logsCmd = "kubectl logs " + nameArg + " -n " + nsArg + " --tail=" + safeTail;
                if (container != null && !container.isBlank()) {
                    logsCmd += " -c " + escapeShellArg(container);
                }
                logsOut = executeCommandWithTimeout(logsCmd, 12);
            } catch (Exception ignored) {
            }

            result.put("success", true);
            result.put("namespace", namespace);
            result.put("name", name);
            result.put("describe", describeOut == null ? "" : describeOut);
            result.put("podJson", podJson != null ? podJson : (jsonOut == null ? "" : jsonOut));
            result.put("events", eventsOut == null ? "" : eventsOut);
            result.put("logs", logsOut == null ? "" : logsOut);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error fetching pod details: " + e.getMessage());
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

    private String executeCommandWithTimeout(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb;
            if (isWindows()) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            return output.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String escapeShellArg(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    // For field-selector value, don't quote; just escape quotes/backslashes a bit.
    private String escapeShellBareArg(String value) {
        String v = value == null ? "" : value;
        return v.replace("\"", "\\\"").replace("\\", "\\\\");
    }
}
