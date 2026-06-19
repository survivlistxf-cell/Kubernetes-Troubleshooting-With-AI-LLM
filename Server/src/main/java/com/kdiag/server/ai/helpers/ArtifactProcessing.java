package com.kdiag.server.ai.helpers;

import java.util.List;

import com.kdiag.server.protocol.KdiagModels.Artifact;

public class ArtifactProcessing {
    // -------------------------------------------------------------------------
    // Artifact processing
    // -------------------------------------------------------------------------

    // Per-section size caps, applied at parse time so a single pod dump is reduced to its
    // high-signal sections BEFORE computeArtifactBudget. Expressed as FRACTIONS of the live
    // input capacity (budgetInputChars, derived from num_ctx) so they scale when num_ctx is
    // switched. JSON is never sent (redundant with DESCRIBE, far more tokens), so it is not parsed.
    private static final double DESCRIBE_CAP_FRACTION = 0.10;
    private static final double LOGS_CAP_FRACTION     = 0.10;
    private static final double EVENTS_CAP_FRACTION   = 0.06;

    private static int capDescribe(int budgetInputChars) { return (int) (budgetInputChars* DESCRIBE_CAP_FRACTION); }
    private static int capLogs(int budgetInputChars)     { return (int) (budgetInputChars * LOGS_CAP_FRACTION); }
    private static int capEvents(int budgetInputChars)   { return (int) (budgetInputChars * EVENTS_CAP_FRACTION); }

    // "--- POD: <ns>/<name> ---" delimits pods; "[DESCRIBE]"/"[EVENTS]"/"[LOGS]" delimit sections.
    private static final java.util.regex.Pattern POD_MARKER =
            java.util.regex.Pattern.compile("(?m)^---\\s*POD:\\s*(.+?)\\s*---\\s*$");
    private static final java.util.regex.Pattern SECTION_MARKER =
            java.util.regex.Pattern.compile("(?m)^\\[(DESCRIBE|EVENTS|LOGS)\\]\\s*$");

    public static List<Artifact> processArtifacts(List<Artifact> artifacts, int budgetInputChars) {
        if (artifacts == null)
            return List.of();
        List<Artifact> result = new java.util.ArrayList<>();
        for (Artifact a : artifacts) {
            String content = (a == null) ? null : a.getContent();
            if (content == null) {
                if (a != null) result.add(a);
                continue;
            }
            if (content.contains("--- POD:") || content.contains("=== BULK POD CONTEXT")) {
                result.addAll(splitBulkPodContext(a, budgetInputChars));     // current frontend format
            } else if (content.contains("--- kubectl")) {
                result.addAll(splitStructuredContent(a));  // legacy format, kept for back-compat
            } else {
                result.add(a);
            }
        }
        return result;
    }

     /**
     * Parses the frontend "BULK POD CONTEXT" blob into one typed Artifact per pod-section.
     * Two levels: pods delimited by "--- POD: ns/name ---", sections by [DESCRIBE]/[JSON]/[EVENTS]/[LOGS].
     * Per-section caps are applied here so no single pod can dominate the artifact budget.
     */
    private static List<Artifact> splitBulkPodContext(Artifact original, int budgetInputChars) {
        String content = original.getContent();
        List<Artifact> parts = new java.util.ArrayList<>();

        java.util.regex.Matcher pm = POD_MARKER.matcher(content);
        List<Integer> mStart = new java.util.ArrayList<>();
        List<Integer> mEnd = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>();
        while (pm.find()) {
            mStart.add(pm.start());
            mEnd.add(pm.end());
            ids.add(pm.group(1).trim());
        }
        if (ids.isEmpty()) {
            return List.of(original); // header present but no real pod block; leave untouched
        }

        for (int i = 0; i < ids.size(); i++) {
            int bodyStart = mEnd.get(i);
            int bodyEnd = (i + 1 < ids.size()) ? mStart.get(i + 1) : content.length();
            parsePodBlock(ids.get(i), content.substring(bodyStart, bodyEnd), parts, budgetInputChars);
        }
        return parts.isEmpty() ? List.of(original) : parts;
    }

    /** Splits one pod block (status line + [SECTION] markers) into capped, typed artifacts. */
    private static void parsePodBlock(String podId, String body, List<Artifact> out, int budgetInputChars) {
        java.util.regex.Matcher sm = SECTION_MARKER.matcher(body);
        List<Integer> sStart = new java.util.ArrayList<>();
        List<Integer> sEnd = new java.util.ArrayList<>();
        List<String> tags = new java.util.ArrayList<>();
        while (sm.find()) {
            sStart.add(sm.start());
            sEnd.add(sm.end());
            tags.add(sm.group(1));
        }

        // Everything before the first section marker is the "Status: ..." header line.
        String preamble = (tags.isEmpty() ? body : body.substring(0, sStart.get(0))).strip();
        String statusLine = preamble.isBlank() ? "" : preamble.lines().findFirst().orElse("").strip();

        if (tags.isEmpty()) {
            if (!statusLine.isBlank())
                out.add(makeArtifact("pod_status", podId, statusLine));
            return;
        }

        // Collect raw sections (last duplicate wins).
        java.util.Map<String, String> sections = new java.util.LinkedHashMap<>();
        for (int i = 0; i < tags.size(); i++) {
            int cStart = sEnd.get(i);
            int cEnd = (i + 1 < tags.size()) ? sStart.get(i + 1) : body.length();
            sections.put(tags.get(i), body.substring(cStart, cEnd).strip());
        }

        // Emit in priority order so FIFO budgeting favors the highest-signal sections first.
        boolean statusAttached = false;
        for (String tag : new String[]{"DESCRIBE", "EVENTS", "LOGS"}) {
            String raw = sections.get(tag);
            if (raw == null || raw.isBlank())
                continue;
            String capped = capSection(tag, raw, budgetInputChars);
            if (!statusAttached && !statusLine.isBlank()) {
                capped = statusLine + "\n" + capped; // keep Restarts/Ready next to the first section
                statusAttached = true;
            }
            out.add(makeArtifact(typeForTag(tag), podId, capped));
        }
        if (!statusAttached && !statusLine.isBlank())
            out.add(makeArtifact("pod_status", podId, statusLine));
    }

    private static String capSection(String tag, String s, int budgetInputChars) {
        switch (tag) {
            case "DESCRIBE": return compressDescribe(s, capDescribe(budgetInputChars)); // diagnostic sections only
            case "LOGS":     return compressLogs(s, capLogs(budgetInputChars));         // errors + stack context only
            case "EVENTS":   return compressEvents(s, capEvents(budgetInputChars));     // Warning events only
            default:         return compressDescribe(s, capDescribe(budgetInputChars));
        }
    }

    // Top-level keys in `kubectl describe pod` worth keeping. Everything else (Labels,
    // Annotations, Tolerations, Volumes, Node-Selectors, Start Time, ...) is metadata noise.
    private static final java.util.Set<String> DESCRIBE_KEEP_KEYS = java.util.Set.of(
            "name", "namespace", "node", "status", "controlled by", "containers",
            "init containers", "ephemeral containers", "conditions", "events", "ip", "ips");

    /**
     * Compresses `kubectl describe pod` to the diagnostic core: keeps Status, Containers
     * (State/Last State/Reason/Restart Count), Conditions and the Events block; drops metadata
     * blocks like Labels, Annotations, Tolerations, Volumes. A block is the top-level key line
     * (column 0) plus its indented children. Falls back to the raw text if nothing matched.
     */
    private static String compressDescribe(String describe, int cap) {
        String[] lines = describe.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean keepCurrent = false;
        boolean droppedAny = false;
        for (String line : lines) {
            boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0));
            if (topLevel) {
                int colon = line.indexOf(':');
                String key = (colon > 0 ? line.substring(0, colon) : line).trim().toLowerCase();
                keepCurrent = DESCRIBE_KEEP_KEYS.contains(key);
                if (!keepCurrent) { droppedAny = true; continue; }
            }
            if (keepCurrent) sb.append(line).append('\n');
        }
        if (sb.length() == 0) return headCap(describe, cap); // unexpected format → keep raw, capped
        String out = droppedAny
                ? "[compressed: Status/Containers/Conditions/Events kept, metadata dropped]\n" + sb
                : sb.toString();
        return headCap(out, cap);
    }

    // Lines worth keeping from logs: error/warning signal. Everything else is INFO noise.
    private static final java.util.regex.Pattern LOG_SIGNAL = java.util.regex.Pattern.compile(
            "(?i)\\b(error|err|warn|warning|fail|failed|failure|exception|panic|fatal|crash|" +
            "oom|killed|back-?off|refused|timeout|timed out|denied|unhealthy|cannot|could not|" +
            "unable|no such|not found|exit code|exitcode|caused by)\\b");

    /**
     * Compresses raw logs to the diagnostic signal: keeps error/warning lines plus the
     * stack-frame lines that follow them, drops repeated duplicates, and discards INFO noise.
     * If no error/warning line is found, falls back to keeping the recent tail.
     */
    private static String compressLogs(String logs, int cap) {
        String[] lines = logs.split("\n");
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || !LOG_SIGNAL.matcher(t).find()) continue;
            if (seen.merge(t, 1, Integer::sum) > 1) continue; // emit each distinct error once
            any = true;
            sb.append(t).append('\n');
            // attach following stack-frame / "Caused by" continuation lines
            for (int j = i + 1; j < lines.length && j <= i + 8; j++) {
                String f = lines[j].stripLeading();
                if (f.startsWith("at ") || f.startsWith("Caused by") || f.startsWith("...")
                        || (lines[j].startsWith("\t") || lines[j].startsWith("    "))) {
                    sb.append("  ").append(f.strip()).append('\n');
                    seen.merge(f.strip(), 1, Integer::sum); // don't re-emit it as its own signal line
                } else {
                    break;
                }
            }
            if (sb.length() >= cap) break;
        }
        if (!any) return tailCap(logs, cap); // clean logs → keep recent activity
        return headCap("[compressed: error/warning lines + stack context, duplicates dropped]\n"
                + sb, cap);
    }

    private static final java.util.regex.Pattern EVENT_WARNING =
            java.util.regex.Pattern.compile("\\bWarning\\b");

    /**
     * Compresses kubectl events to Warning rows only (drops Normal noise), keeping the header.
     * If there are no warnings, keeps the section as-is (it is usually short).
     */
    private static String compressEvents(String events, int cap) {
        String[] lines = events.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean anyWarning = false;
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            boolean isHeader = t.startsWith("LAST SEEN") || t.startsWith("TYPE");
            if (isHeader) {
                sb.append(t).append('\n');
            } else if (EVENT_WARNING.matcher(t).find()) {
                sb.append(t).append('\n');
                anyWarning = true;
            }
        }
        if (!anyWarning) return headCap(events, cap); // no warnings → keep full (short anyway)
        return headCap("[compressed: Warning events only]\n" + sb, cap);
    }

    private static String typeForTag(String tag) {
        switch (tag) {
            case "DESCRIBE": return "pod_describe";
            case "EVENTS":   return "pod_events";
            case "LOGS":     return "pod_logs";
            default:         return "pod_section";
        }
    }

    private static String headCap(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " chars]";
    }

    private static String tailCap(String s, int max) {
        if (s.length() <= max) return s;
        return "...[truncated " + (s.length() - max) + " chars]\n" + s.substring(s.length() - max);
    }

    private static Artifact makeArtifact(String type, String target, String content) {
        Artifact a = new Artifact();
        a.setType(type);
        a.setTarget(target);
        a.setContent(content);
        return a;
    }

    private static List<Artifact> splitStructuredContent(Artifact original) {
        List<Artifact> parts = new java.util.ArrayList<>();
        String content = original.getContent();

        String[] sections = content.split("(?m)^--- ");
        for (String section : sections) {
            if (section.isBlank())
                continue;

            Artifact p = new Artifact();
            if (section.toLowerCase().startsWith("kubectl describe")) {
                p.setType("pod_describe");
                p.setContent(section.substring(section.indexOf("\n") + 1));
            } else if (section.toLowerCase().startsWith("logs") || section.toLowerCase().startsWith("kubectl logs")) {
                p.setType("pod_logs");
                p.setContent(section.substring(section.indexOf("\n") + 1));
            } else if (section.toLowerCase().startsWith("events")) {
                p.setType("pod_events");
                p.setContent(section.substring(section.indexOf("\n") + 1));
            } else {
                p.setType(original.getType());
                p.setContent(section);
            }
            parts.add(p);
        }
        return parts.isEmpty() ? List.of(original) : parts;
    }

}
