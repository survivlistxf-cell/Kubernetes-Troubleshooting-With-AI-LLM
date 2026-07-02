package com.kdiag.server.docs;

import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.entities.ProblemResolution;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import com.kdiag.server.repositories.ProblemResolutionRepository;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class KubernetesDynamicSearcher {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesDynamicSearcher.class);
    private static final int BRAVE_TIMEOUT_MS = 10000;
    private static final int MAX_SEARCH_RESULTS = 4;
    // Default per-dynamic-doc cap used when no explicit budget is passed (backward-compat).
    // Callers that know the live context window (AiEngine) pass a num_ctx-scaled value instead.
    private static final int MAX_DYNAMIC_DOC_CHARS = 10000;
    // Hard cap against pathological pages returned by Readability (e.g. mirror sites with huge content)
    static final int ABSOLUTE_PERSIST_CHAR_CAP = 500_000;

    private final KubernetesDocPageRepository docRepository;
    private final ProblemResolutionRepository resolutionRepository;
    private final ChunkRetriever chunkRetriever;
    private final String braveApiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Result of a dynamic search: the assembled doc context plus the URLs it was built from. */
    public record SearchResult(String context, List<String> urls) {}

    public KubernetesDynamicSearcher(KubernetesDocPageRepository docRepository,
                                     ProblemResolutionRepository resolutionRepository,
                                     @Qualifier("activeChunkRetriever") ChunkRetriever chunkRetriever,
                                     @Value("${BRAVE_API_KEY:}") String braveApiKey) {
        this.docRepository = docRepository;
        this.resolutionRepository = resolutionRepository;
        this.chunkRetriever = chunkRetriever;
        this.braveApiKey = braveApiKey;
    }

    /**
     * Conducts a Brave Search API query bounded to kubernetes.io/docs, then fetches the
     * top result pages and saves them to the knowledge base.
     *
     * <p>This previously scraped DuckDuckGo's HTML endpoint, but DDG returns HTTP 202 plus
     * an anti-bot form page for requests originating from datacenter IPs (such as the
     * cluster), so no results could ever be parsed. Brave exposes a stable JSON API.
     *
     * <p>Requires the {@code BRAVE_API_KEY} environment variable. When it is absent, dynamic
     * web search degrades gracefully to a no-op, which keeps the system usable in
     * air-gapped deployments where outbound egress is disabled.
     */
    /** Backward-compatible entry point using the default per-doc cap. */
    public SearchResult searchAndSave(String conversationId, String query) {
        return searchAndSave(conversationId, query, MAX_DYNAMIC_DOC_CHARS);
    }

    /**
     * Same as {@link #searchAndSave(String, String)} but with an explicit per-dynamic-doc char
     * cap, so callers can scale how much web documentation is pulled with the live context window.
     */
    public SearchResult searchAndSave(String conversationId, String query, int maxDocChars) {
        logger.info("Web search (Brave) for: {}", query);
        List<String> foundUrls = new ArrayList<>();
        StringBuilder newContext = new StringBuilder();

        if (braveApiKey == null || braveApiKey.isBlank()) {
            logger.warn("BRAVE_API_KEY not configured; dynamic web search disabled (returning empty context).");
            return new SearchResult(newContext.toString(), foundUrls);
        }

        try {
            String fullQuery = "site:kubernetes.io/docs " + query;
            String braveUrl = "https://api.search.brave.com/res/v1/web/search?count=10&q="
                    + URLEncoder.encode(fullQuery, StandardCharsets.UTF_8);
            logger.info("Asking Brave: q='{}'", fullQuery);

            Connection.Response resp = Jsoup.connect(braveUrl)
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", braveApiKey)
                    .timeout(BRAVE_TIMEOUT_MS )
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .method(Connection.Method.GET)
                    .execute();

            String body = resp.body();
            int bodyLength = body == null ? 0 : body.length();

            if (resp.statusCode() != 200) {
                logger.warn("Brave API returned status {} (bodyLength={}). Snippet: {}",
                        resp.statusCode(), bodyLength,
                        body == null ? "" : body.substring(0, Math.min(500, body.length())));
                return new SearchResult(newContext.toString(), foundUrls);
            }

            JsonNode results = objectMapper.readTree(body == null ? "{}" : body)
                    .path("web").path("results");
            logger.info("Brave response for '{}': statusCode={}, bodyLength={}, results={}",
                    query, resp.statusCode(), bodyLength, results.isArray() ? results.size() : 0);

            int count = 0;
            if (results.isArray()) {
                for (JsonNode r : results) {
                    if (count >= MAX_SEARCH_RESULTS) break; // Bounded set to avoid LLM context overflow
                    String url = r.path("url").asText("");
                    if (url.isBlank()) continue;
                    logger.info("Brave candidate URL (pre-filter): {}", url);

                    if (url.contains("kubernetes.io/docs/")) {
                        logger.info("Discovered URL: {}", url);
                        foundUrls.add(url);

                        String text = fetchAndSaveDoc(url, maxDocChars);
                        if (!text.isEmpty()) {
                            // Deduplicate repetitive paragraphs and limit per dynamic doc to keep prompt size stable
                            String deduped = dedupeParagraphs(text, maxDocChars);
                            newContext.append("## Source: ").append(url).append("\n").append(deduped).append("\n\n");
                            count++;
                        }
                    }
                }
            }

            // Save problem resolution mapping (Link this conversation to the discovered URLs)
            if (!foundUrls.isEmpty()) {
                String joinedUrls = String.join("\n", foundUrls);
                ProblemResolution res = new ProblemResolution(conversationId == null ? "anonymous" : conversationId, query, joinedUrls);
                resolutionRepository.save(res);
            }

        } catch (Exception e) {
            logger.error("Search failed", e);
        }

        // Log average stored doc length for observability
        try {
            int avg = computeAverageDocLength();
            logger.info("Average stored Kubernetes doc length: {} chars", avg);
        } catch (Exception ignored) {
        }

        return new SearchResult(newContext.toString(), foundUrls);
    }

    private String fetchAndSaveDoc(String url, int maxDocChars) {
        Optional<KubernetesDocPage> existing = docRepository.findByUrl(url);
        if (existing.isPresent()) {
            logger.info("URL already in DB: {}", url);
            return truncate(existing.get().getTextContent(), maxDocChars);
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; Kubexplain/1.0)")
                    .timeout(BRAVE_TIMEOUT_MS )
                    .get();

            ReadabilityExtractor.Result result = ReadabilityExtractor.extract(url, doc, this::legacyExtract);
            String content = result.text();
            String title = result.title();
            if (title == null || title.isBlank()) title = "Discovered Kubernetes Doc";

            if (content.length() > ABSOLUTE_PERSIST_CHAR_CAP) {
                logger.warn("Dynamic page {} is {} chars — capping at {}", url, content.length(), ABSOLUTE_PERSIST_CHAR_CAP);
                content = content.substring(0, ABSOLUTE_PERSIST_CHAR_CAP);
            }

            KubernetesDocPage page = new KubernetesDocPage(url, title, content, true);
            docRepository.save(page);
            chunkRetriever.indexPage(page);

            // For dynamic inclusion, deduplicate and cap the snippet to avoid sending huge prompts
            String deduped = dedupeParagraphs(content, maxDocChars);
            return deduped;
        } catch (Exception e) {
            logger.error("Failed to fetch {}", url, e);
            return "";
        }
    }

    private String legacyExtract(Document doc) {
        Element main = doc.selectFirst("main, article, .td-content, #content");
        if (main == null) main = doc.body();
        StringBuilder sb = new StringBuilder();
        for (Element el : main.select("h1, h2, h3, p, pre, code, li")) {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                if (el.tagName().equals("pre") || el.tagName().equals("code")) {
                    sb.append("`").append(text).append("`\n");
                } else if (el.tagName().matches("h[123]")) {
                    sb.append("\n").append(text).append("\n");
                } else {
                    sb.append(text).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Simple deduplication: split content by double newlines or single newlines into paragraphs,
     * keep first occurrence of each paragraph (preserving order) and stop when reaching maxChars.
     */
    private String dedupeParagraphs(String content, int maxChars) {
        if (content == null || content.isBlank()) return "";
        String[] parts = content.split("\\n\\n|\\r\\n\\r\\n");
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String para = p.trim();
            if (para.isEmpty()) continue;
            // Normalize whitespace for matching
            String norm = para.replaceAll("\\s+", " ").trim();
            if (seen.contains(norm)) continue;
            seen.add(norm);
            if (sb.length() + norm.length() + 2 > maxChars) break;
            sb.append(norm).append("\n\n");
        }
        String out = sb.toString().trim();
        return out.length() <= maxChars ? out : out.substring(0, maxChars) + "...[truncated]";
    }

    /**
     * Compute average length (chars) of all stored KubernetesDocPage.textContent.
     */
    public int computeAverageDocLength() {
        List<KubernetesDocPage> pages = docRepository.findAll();
        if (pages == null || pages.isEmpty()) return 0;
        long total = 0;
        int count = 0;
        for (KubernetesDocPage p : pages) {
            String t = p.getTextContent();
            if (t == null) continue;
            total += t.length();
            count++;
        }
        return count == 0 ? 0 : (int) (total / count);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
