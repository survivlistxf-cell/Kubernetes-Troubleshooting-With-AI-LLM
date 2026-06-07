package com.kdiag.server.docs;

import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.docs.index.DocChunk;
import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // Hard cap against pathological pages returned by Readability (e.g. mirror sites with huge content)
    static final int ABSOLUTE_PERSIST_CHAR_CAP = 500_000;

    /**
     * Short terms that are highly diagnostic in Kubernetes context and must NOT be
     * filtered out by the {@code w.length() >= 4} guard in {@link #extractKeywords}.
     * Standard English stop-word lists never include these, but the length guard
     * would silently drop e.g. "pod", "dns", "oom", "cni", "api", "tls".
     */
    private static final Set<String> K8S_ESSENTIAL_SHORT_TERMS = Set.of(
            "pod", "dns", "oom", "cni", "api", "rbac", "tls", "ssl", "tcp", "udp", "ip",
            "etcd", "cri", "csi", "crd", "cmd", "env", "ctx", "gpu", "cpu", "mem",
            "nfs", "cwd", "pvc", "pv"
    );

    private final KubernetesDocPageRepository repository;
    private final ChunkRetriever chunkRetriever;
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

    public KubernetesDocsScraper(KubernetesDocPageRepository repository,
                                 @Qualifier("activeChunkRetriever") ChunkRetriever chunkRetriever) {
        this.repository     = repository;
        this.chunkRetriever = chunkRetriever;
        this.restTemplate   = new RestTemplate();
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

                    ReadabilityExtractor.Result result = ReadabilityExtractor.extract(url, doc, this::legacyExtract);
                    String text = result.text();
                    String title = result.title();
                    if (title == null || title.isBlank()) {
                        title = "Kubernetes Documentation";
                    }

                    if (text.length() > ABSOLUTE_PERSIST_CHAR_CAP) {
                        logger.warn("Page {} is {} chars — capping at {}", url, text.length(), ABSOLUTE_PERSIST_CHAR_CAP);
                        text = text.substring(0, ABSOLUTE_PERSIST_CHAR_CAP);
                    }

                    KubernetesDocPage page = new KubernetesDocPage(url, title, text, false);
                    repository.save(page);
                    chunkRetriever.indexPage(page);
                } catch (Exception e) {
                    logger.error("Failed to fetch static doc {}", url, e);
                }
            }
        }
    }

    private String legacyExtract(Document doc) {
        Element root = doc.selectFirst("main, article, .td-content, #content");
        if (root == null) root = doc.body();
        return extractCleanText(root);
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
     * BM25-ranked retrieval using the Lucene chunk index.
     * Returns an empty string if the index returns no results; caller should
     * fall back to {@link #getRelevantDocs(String)}.
     */
    public String getRelevantDocsByBm25(String userMessage, int maxContextChars) {
        List<DocChunk> chunks = chunkRetriever.search(userMessage, 12);
        if (chunks.isEmpty()) return "";
        return assembleContext(chunks, maxContextChars);
    }

    /**
     * Boost-aware BM25 retrieval: delegates to
     * {@link com.kdiag.server.docs.index.LuceneChunkIndex#search(String, int, Set)}
     * so that chunks from previously-liked URLs are scored 1.5× higher before
     * trimming to the top-K result set.
     *
     * <p>Returns an empty string when the index has no matching chunks; caller
     * should fall back to {@link #getRelevantDocs(String)}.
     */
    public String getRelevantDocsByBm25Boosted(String userMessage, int maxContextChars,
                                               Set<String> boostedUrls) {
        List<DocChunk> chunks = chunkRetriever.search(userMessage, 12, boostedUrls);
        if (chunks.isEmpty()) return "";
        return assembleContext(chunks, maxContextChars);
    }

    /**
     * Assembles a context block from the given chunks, grouped by URL and
     * capped at {@code maxContextChars} total.
     */
    private String assembleContext(List<DocChunk> chunks, int maxContextChars) {
        // Group chunks by URL, preserving BM25 rank order of first occurrence
        Map<String, List<DocChunk>> byUrl = new LinkedHashMap<>();
        for (DocChunk chunk : chunks) {
            byUrl.computeIfAbsent(chunk.url(), k -> new ArrayList<>()).add(chunk);
        }

        StringBuilder sb = new StringBuilder("=== Relevant Kubernetes Documentation ===\n");
        int chars = 0;
        for (Map.Entry<String, List<DocChunk>> entry : byUrl.entrySet()) {
            String url = entry.getKey();
            List<DocChunk> urlChunks = entry.getValue();
            String title = urlChunks.get(0).title();

            StringBuilder chunkText = new StringBuilder();
            for (DocChunk c : urlChunks) {
                if (chunkText.length() > 0) chunkText.append("\n\n");
                chunkText.append(c.text());
            }

            String block = "## " + title + "\n" + chunkText + "\nSource: " + url + "\n\n";
            if (block.length() > maxContextChars) {
                block = block.substring(0, maxContextChars) + "...[document truncated due to length]\n\n";
            }
            if (chars + block.length() > maxContextChars && chars > 0) break;

            sb.append(block);
            chars += block.length();
        }
        sb.append("==========================================\n");
        return sb.toString();
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

    /**
     * Re-fetches pages whose stored text was truncated at the old 20 000-char ceiling.
     * Rate-limited to 1 request per second to avoid hammering kubernetes.io.
     * Returns the number of pages successfully refreshed.
     */
    public int refreshStalePages() {
        List<KubernetesDocPage> all = repository.findAll();
        List<KubernetesDocPage> stale = all.stream()
                .filter(p -> p.getTextContent() != null && p.getTextContent().length() == 20000)
                .toList();

        if (stale.isEmpty()) {
            logger.info("No stale (truncated-at-20k) pages found.");
            return 0;
        }

        logger.warn("Found {} suspected-truncated pages: {}",
                stale.size(), stale.stream().map(KubernetesDocPage::getUrl).toList());

        int refreshed = 0;
        for (KubernetesDocPage page : stale) {
            try {
                logger.info("Re-fetching stale page: {}", page.getUrl());
                Document doc = Jsoup.connect(page.getUrl())
                        .userAgent("Mozilla/5.0 (compatible; Kubexplain/1.0)")
                        .timeout(DOC_FETCH_TIMEOUT_MS)
                        .get();

                ReadabilityExtractor.Result result =
                        ReadabilityExtractor.extract(page.getUrl(), doc, this::legacyExtract);
                String text = result.text();
                String title = result.title();
                if (title == null || title.isBlank()) title = page.getTitle();

                if (text.length() > ABSOLUTE_PERSIST_CHAR_CAP) {
                    logger.warn("Stale page {} is {} chars — capping at {}",
                            page.getUrl(), text.length(), ABSOLUTE_PERSIST_CHAR_CAP);
                    text = text.substring(0, ABSOLUTE_PERSIST_CHAR_CAP);
                }

                page.setTextContent(text);
                page.setTitle(title);
                page.setLastScraped(LocalDateTime.now());
                repository.save(page);
                chunkRetriever.indexPage(page);
                refreshed++;

                Thread.sleep(1000); // rate limit: 1 req/second
            } catch (Exception e) {
                logger.error("Failed to refresh stale page {}", page.getUrl(), e);
            }
        }

        logger.info("Stale-page refresh complete: {}/{} pages updated", refreshed, stale.size());
        return refreshed;
    }

    /**
     * Extracts query keywords from {@code text} for the legacy keyword-scoring
     * fallback path.
     *
     * <p>Rules (applied after lowercasing):
     * <ol>
     *   <li>Skip empty tokens.</li>
     *   <li>Skip tokens present in the English stop-word list.</li>
     *   <li>Accept tokens of 4+ characters unconditionally.</li>
     *   <li>Accept shorter tokens only if they appear in
     *       {@link #K8S_ESSENTIAL_SHORT_TERMS} (e.g. "pod", "dns", "oom", "api").
     *       This prevents highly diagnostic K8s abbreviations from being silently
     *       dropped by the length guard.</li>
     * </ol>
     */
    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank())
            return List.of();
        ensureStopwordsLoaded();
        String lower = text.toLowerCase();
        String[] words = lower.split("[^a-zA-Z0-9àâîșțăÀÂÎȘȚĂ]+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (stopWords.contains(w)) continue;
            if (w.length() >= 4) {
                keywords.add(w);
                continue;
            }
            // Short token: only keep if it is a known K8s abbreviation
            if (K8S_ESSENTIAL_SHORT_TERMS.contains(w)) {
                keywords.add(w);
            }
        }
        logger.debug("extractKeywords: {} tokens → {} keywords", words.length, keywords.size());
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
