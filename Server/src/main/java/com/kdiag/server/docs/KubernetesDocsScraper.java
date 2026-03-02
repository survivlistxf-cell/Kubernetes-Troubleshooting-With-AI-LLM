package com.kdiag.server.docs;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches Kubernetes debug documentation pages.
 * On each chat request, searches for sections relevant to the user's message.
 */
@Component
public class KubernetesDocsScraper {

    // Pages to index - ordered by relevance
    private static final List<String> DOC_URLS = List.of(
        "https://kubernetes.io/docs/tasks/debug/debug-application/",
        "https://kubernetes.io/docs/tasks/debug/debug-application/debug-pods/",
        "https://kubernetes.io/docs/tasks/debug/debug-application/debug-service/",
        "https://kubernetes.io/docs/tasks/debug/debug-application/debug-running-pod/",
        "https://kubernetes.io/docs/tasks/debug/debug-cluster/"
    );

    // Cache entries: url -> (text, fetchedAt)
    private final Map<String, CachedPage> cache = new ConcurrentHashMap<>();

    // Cache TTL options:
    private static final long CACHE_TTL_SECONDS = 3600;       // 1 oră (default)
    // private static final long CACHE_TTL_SECONDS = 86400;   // 24 ore (pentru producție)
    // private static final long CACHE_TTL_SECONDS = 300;     // 5 minute (pentru development/test)

    // Max chars to inject per request (keep prompt size sane)
    private static final int MAX_CONTEXT_CHARS = 3000;

    private static class CachedPage {
        final String text;
        final List<Section> sections;
        final Instant fetchedAt;

        CachedPage(String text, List<Section> sections) {
            this.text = text;
            this.sections = sections;
            this.fetchedAt = Instant.now();
        }

        boolean isExpired() {
            return Instant.now().getEpochSecond() - fetchedAt.getEpochSecond() > CACHE_TTL_SECONDS;
        }
    }

    private static class Section {
        final String heading;
        final String content;
        final String url;

        Section(String heading, String content, String url) {
            this.heading = heading;
            this.content = content;
            this.url = url;
        }
    }

    /**
     * Returns relevant documentation snippets for the given user message.
     * Falls back to static snippet if all fetches fail.
     */
    public String getRelevantDocs(String userMessage) {
        List<Section> allSections = new ArrayList<>();

        for (String url : DOC_URLS) {
            try {
                CachedPage page = getOrFetch(url);
                if (page != null) {
                    allSections.addAll(page.sections);
                }
            } catch (Exception e) {
                System.err.println("[KubernetesDocsScraper] Failed to fetch " + url + ": " + e.getMessage());
            }
        }

        if (allSections.isEmpty()) {
            return getFallbackSnippet();
        }

        // Score sections by keyword relevance
        List<Section> ranked = rankSections(allSections, userMessage);

        // Build context string from top sections
        StringBuilder sb = new StringBuilder();
        sb.append("=== Relevant Kubernetes Documentation ===\n");
        int chars = 0;
        for (Section s : ranked) {
            if (chars >= MAX_CONTEXT_CHARS) break;
            String entry = "## " + s.heading + "\n" + s.content + "\nSource: " + s.url + "\n\n";
            sb.append(entry);
            chars += entry.length();
        }
        sb.append("==========================================\n");

        return sb.toString();
    }

    private CachedPage getOrFetch(String url) throws Exception {
        CachedPage cached = cache.get(url);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        System.out.println("[KubernetesDocsScraper] Fetching: " + url);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; Kubexplain/1.0)")
                .timeout(8000)
                .get();

        // Extract main content area
        Element main = doc.selectFirst("main, article, .td-content, #content");
        if (main == null) {
            main = doc.body();
        }

        List<Section> sections = extractSections(main, url);
        String fullText = main.text();

        CachedPage page = new CachedPage(fullText, sections);
        cache.put(url, page);
        return page;
    }

    private List<Section> extractSections(Element root, String pageUrl) {
        List<Section> sections = new ArrayList<>();

        // Find all headings (h2, h3) and collect their following content
        Elements headings = root.select("h2, h3");

        for (Element heading : headings) {
            String headingText = heading.text().trim();
            if (headingText.isEmpty()) continue;

            // Collect text from sibling elements until next heading
            StringBuilder contentSb = new StringBuilder();
            Element next = heading.nextElementSibling();
            int limit = 0;
            while (next != null && !next.tagName().matches("h[123]") && limit < 8) {
                String t = next.text().trim();
                if (!t.isEmpty()) {
                    contentSb.append(t).append("\n");
                }
                // Also collect code blocks
                Elements codes = next.select("code, pre");
                for (Element code : codes) {
                    String codeText = code.text().trim();
                    if (!codeText.isEmpty() && !contentSb.toString().contains(codeText)) {
                        contentSb.append("`").append(codeText).append("`\n");
                    }
                }
                next = next.nextElementSibling();
                limit++;
            }

            String content = contentSb.toString().trim();
            if (!content.isEmpty()) {
                sections.add(new Section(headingText, content, pageUrl));
            }
        }

        // If no headings found, add the whole page as one section
        if (sections.isEmpty()) {
            String text = root.text();
            if (!text.isBlank()) {
                sections.add(new Section("Kubernetes Debugging", truncate(text, 1500), pageUrl));
            }
        }

        return sections;
    }

    /**
     * Rank sections by keyword overlap with userMessage.
     * Simple TF-style scoring — no ML needed.
     */
    private List<Section> rankSections(List<Section> sections, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return sections.subList(0, Math.min(3, sections.size()));
        }

        String lower = userMessage.toLowerCase();
        // Extract keywords (words > 3 chars, skip stop words)
        Set<String> stopWords = Set.of("the", "and", "for", "that", "this", "with", "from",
                "are", "was", "have", "has", "can", "will", "not", "what", "how", "why",
                "cum", "care", "sunt", "sau", "din", "pentru", "mai");  // ← "este" apare o singură dată

        String[] words = lower.split("[^a-zA-Z0-9àâîșțăÀÂÎȘȚĂ]+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() > 3 && !stopWords.contains(w)) {
                keywords.add(w);
            }
        }

        // Score each section
        List<Map.Entry<Section, Integer>> scored = new ArrayList<>();
        for (Section s : sections) {
            String combined = (s.heading + " " + s.content).toLowerCase();
            int score = 0;
            for (String kw : keywords) {
                int idx = 0;
                while ((idx = combined.indexOf(kw, idx)) != -1) {
                    score++;
                    idx += kw.length();
                }
            }
            // Boost heading matches
            String headingLower = s.heading.toLowerCase();
            for (String kw : keywords) {
                if (headingLower.contains(kw)) score += 5;
            }
            scored.add(Map.entry(s, score));
        }

        scored.sort((a, b) -> b.getValue() - a.getValue());

        // Return top 4 sections
        List<Section> result = new ArrayList<>();
        for (int i = 0; i < Math.min(4, scored.size()); i++) {
            result.add(scored.get(i).getKey());
        }
        return result;
    }

    private String getFallbackSnippet() {
        return "=== Kubernetes Debugging Checklist (offline fallback) ===\n"
                + "Source: https://kubernetes.io/docs/tasks/debug/debug-application/\n"
                + "- `kubectl get pods -n <ns>` — check STATUS and RESTARTS\n"
                + "- `kubectl describe pod <pod> -n <ns>` — inspect Events section\n"
                + "- `kubectl logs <pod> -n <ns> --tail=200 [--previous]` — read container logs\n"
                + "- `kubectl exec -it <pod> -n <ns> -- /bin/sh` — shell into container\n"
                + "==========================================\n";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    /** Force-refresh the cache (e.g., on demand via admin endpoint). */
    public void clearCache() {
        cache.clear();
    }

    /** Returns cache status for health checks. */
    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("cachedPages", cache.size());
        status.put("totalUrls", DOC_URLS.size());
        cache.forEach((url, page) -> {
            status.put(url, Map.of(
                "sections", page.sections.size(),
                "expired", page.isExpired(),
                "ageSeconds", Instant.now().getEpochSecond() - page.fetchedAt.getEpochSecond()
            ));
        });
        return status;
    }
}
