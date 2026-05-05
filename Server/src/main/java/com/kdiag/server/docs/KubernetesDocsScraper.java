package com.kdiag.server.docs;

import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Fetches and caches Kubernetes debug documentation pages into DB.
 * On each chat request, searches for pages relevant to the user's message.
 */
@Component
public class KubernetesDocsScraper {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesDocsScraper.class);
    private static final int STOPWORDS_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
    private static final int DOC_FETCH_TIMEOUT_MS = 8000;
    private static final int MAX_CONTEXT_CHARS = 12000;
    private static final int MAX_SCRAPED_DOC_CHARS = 20000;

    private final KubernetesDocPageRepository repository;
    private final RestTemplate restTemplate;

    private Set<String> stopWords = new HashSet<>();
    private long lastStopwordsFetch = 0;

        // Pages to index - ordered by relevance
    private static final List<String> DOC_URLS = List.of(
            "https://kubernetes.io/docs/tasks/debug/debug-application/",
            "https://kubernetes.io/docs/tasks/debug/debug-application/debug-pods/",
            "https://kubernetes.io/docs/tasks/debug/debug-application/debug-service/",
            "https://kubernetes.io/docs/tasks/debug/debug-application/debug-running-pod/",
            "https://kubernetes.io/docs/tasks/debug/debug-cluster/");

    public KubernetesDocsScraper(KubernetesDocPageRepository repository) {
        this.repository = repository;
        this.restTemplate = new RestTemplate();
    }

    private void ensureStopwordsLoaded() {
        // Cache for 24 hours
        if (System.currentTimeMillis() - lastStopwordsFetch > STOPWORDS_CACHE_TTL_MS || stopWords.isEmpty()) {
            try {
                logger.info("Fetching English stop words from NLTK/GitHub API...");
                // Fetch standard english stopwords list from a reliable public source
                String url = "https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/stopwords.zip/stopwords/english";
                String response = restTemplate.getForObject(url, String.class);

                if (response != null) {
                    Set<String> fetched = new HashSet<>();
                    for (String line : response.split("\n")) {
                        if (!line.trim().isEmpty()) {
                            fetched.add(line.trim().toLowerCase());
                        }
                    }
                    this.stopWords = fetched;
                    this.lastStopwordsFetch = System.currentTimeMillis();
                    logger.info("Loaded {} stop words from API.", stopWords.size());
                }
            } catch (Exception e) {
                logger.error("Failed to fetch stopwords from API, falling back to minimal list", e);
                // Minimal fallback in case of no internet on boot
                if (stopWords.isEmpty()) {
                    stopWords = new HashSet<>(
                            Arrays.asList("the", "and", "a", "an", "in", "on", "at", "to", "for", "of", "with"));
                }
            }
        }
    }

    @PostConstruct
    public void initStaticDocs() {
        logger.info("Checking static docs in DB...");
        for (String url : DOC_URLS) {
            if (repository.findByUrl(url).isEmpty()) {
                try {
                    logger.info("Fetching initial static doc: {}", url);
                    Document doc = Jsoup.connect(url)
                            .userAgent("Mozilla/5.0 (compatible; Kubexplain/1.0)")
                            .timeout(DOC_FETCH_TIMEOUT_MS)
                            .get();

                    Element main = doc.selectFirst("main, article, .td-content, #content");
                    if (main == null) {
                        main = doc.body();
                    }

                    String text = extractCleanText(main);
                    String title = doc.title();
                    if (title == null || title.isBlank()) {
                        title = "Kubernetes Documentation";
                    }

                    KubernetesDocPage page = new KubernetesDocPage(url, title, truncate(text, MAX_SCRAPED_DOC_CHARS), false);
                    repository.save(page);
                } catch (Exception e) {
                    logger.error("Failed to fetch static doc {}", url, e);
                }
            }
        }
    }

    private String extractCleanText(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element el : root.select("h1, h2, h3, p, pre, code, li")) {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                // If code, format as markdown
                if (el.tagName().equals("pre") || el.tagName().equals("code")) {
                    sb.append("`").append(text).append("`\n");
                } else if (el.tagName().matches("h[123]")) {
                    sb.append("\n").append(text).append("\n"); // Heading spacing
                } else {
                    sb.append(text).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Returns relevant documentation for the given user message.
     */
    public String getRelevantDocs(String userMessage) {
        List<KubernetesDocPage> allPages = repository.findAll();

        if (allPages.isEmpty()) {
            return getFallbackSnippet();
        }

        List<String> keywords = extractKeywords(userMessage);
        // Score pages by keyword relevance
        List<KubernetesDocPage> ranked = rankPages(allPages, keywords);

        // Build context string from top pages
        StringBuilder sb = new StringBuilder();
        sb.append("=== Relevant Kubernetes Documentation ===\n");
        int chars = 0;
        for (KubernetesDocPage p : ranked) {
            String content = p.getTextContent();
            if (content == null || content.isEmpty())
                continue;

            String entry = "## " + p.getTitle() + "\n" + content + "\nSource: " + p.getUrl() + "\n\n";

            // If entry alone > limit, truncate it to avoid overflow
            if (entry.length() > MAX_CONTEXT_CHARS) {
                entry = entry.substring(0, MAX_CONTEXT_CHARS) + "...[document truncated due to length]\n\n";
            }

            if (chars + entry.length() > MAX_CONTEXT_CHARS && chars > 0) {
                break;
            }

            sb.append(entry);
            chars += entry.length();
        }
        sb.append("==========================================\n");

        return sb.toString();
    }

    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank())
            return List.of();
        ensureStopwordsLoaded();
        String lower = text.toLowerCase();
        String[] words = lower.split("[^a-zA-Z0-9àâîșțăÀÂÎȘȚĂ]+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() > 3 && !stopWords.contains(w)) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    /**
     * Rank pages by keyword overlap with userMessage.
     */
    private List<KubernetesDocPage> rankPages(List<KubernetesDocPage> pages, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return pages.subList(0, Math.min(3, pages.size()));
        }

        // Score each page
        List<Map.Entry<KubernetesDocPage, Integer>> scored = new ArrayList<>();
        for (KubernetesDocPage p : pages) {
            String combined = ((p.getTitle() == null ? "" : p.getTitle()) + " "
                    + (p.getTextContent() == null ? "" : p.getTextContent())).toLowerCase();
            int score = 0;
            for (String kw : keywords) {
                int idx = 0;
                while ((idx = combined.indexOf(kw, idx)) != -1) {
                    score++;
                    idx += kw.length();
                }
            }
            // Boost title matches
            if (p.getTitle() != null) {
                String titleLower = p.getTitle().toLowerCase();
                for (String kw : keywords) {
                    if (titleLower.contains(kw))
                        score += 5;
                }
            }
            // Boost dynamic pages a little extra so they show up if searched
            if (p.isDynamic()) {
                score += 2;
            }
            scored.add(Map.entry(p, score));
        }

        scored.sort((a, b) -> b.getValue() - a.getValue());

        // Return top 3 pages
        List<KubernetesDocPage> result = new ArrayList<>();
        for (int i = 0; i < Math.min(3, scored.size()); i++) {
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
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    public Map<String, Object> getStatus() {
        return Map.of("pagesInDb", repository.count());
    }
}
