package com.kdiag.server.docs;

import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.entities.ProblemResolution;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import com.kdiag.server.repositories.ProblemResolutionRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final int DDG_TIMEOUT_MS = 10000;
    private static final int MAX_SEARCH_RESULTS = 2;
    private static final int MAX_DYNAMIC_DOC_CHARS = 10000;
    // Hard cap against pathological pages returned by Readability (e.g. mirror sites with huge content)
    static final int ABSOLUTE_PERSIST_CHAR_CAP = 500_000;

    private final KubernetesDocPageRepository docRepository;
    private final ProblemResolutionRepository resolutionRepository;
    private final ChunkRetriever chunkRetriever;

    public KubernetesDynamicSearcher(KubernetesDocPageRepository docRepository,
                                     ProblemResolutionRepository resolutionRepository,
                                     @Qualifier("activeChunkRetriever") ChunkRetriever chunkRetriever) {
        this.docRepository = docRepository;
        this.resolutionRepository = resolutionRepository;
        this.chunkRetriever = chunkRetriever;
    }

    /**
     * Conducts a DuckDuckGo HTML search bounded to kubernetes.io,
     * scrapes the top URLs and saves them to knowledge base.
     */
    public String searchAndSave(String conversationId, String query) {
        logger.info("Scraping search results for: {}", query);
        List<String> foundUrls = new ArrayList<>();
        StringBuilder newContext = new StringBuilder();

        try {
            // Use DDG HTML version to bypass JS requirements
            String searchUrl = "https://html.duckduckgo.com/html/?q=site:kubernetes.io/docs+" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            logger.info("Asking DDG: {}", searchUrl);

            Document searchDoc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(DDG_TIMEOUT_MS)
                    .get();

            // DDG HTML snippet URLs are stored inside a.result__url as raw text
            Elements resultAnchors = searchDoc.select("a.result__url");
            int count = 0;
            for (Element a : resultAnchors) {
                if (count >= MAX_SEARCH_RESULTS) break; // Fetch a small bounded set to avoid LLM context overflow
                String displayUrl = a.text().trim();

                // Format URL
                if (!displayUrl.startsWith("http")) {
                    displayUrl = "https://" + displayUrl;
                }

                if (displayUrl.contains("kubernetes.io/docs/")) {
                    logger.info("Discovered URL: {}", displayUrl);
                    foundUrls.add(displayUrl);

                    String text = fetchAndSaveDoc(displayUrl);
                    if (!text.isEmpty()) {
                        // Deduplicate repetitive paragraphs and limit per dynamic doc to keep prompt size stable
                        String deduped = dedupeParagraphs(text, MAX_DYNAMIC_DOC_CHARS);
                        newContext.append("## Source: ").append(displayUrl).append("\n").append(deduped).append("\n\n");
                        count++;
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

        return newContext.toString();
    }

    private String fetchAndSaveDoc(String url) {
        Optional<KubernetesDocPage> existing = docRepository.findByUrl(url);
        if (existing.isPresent()) {
            logger.info("URL already in DB: {}", url);
            return truncate(existing.get().getTextContent(), MAX_DYNAMIC_DOC_CHARS);
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; Kubexplain/1.0)")
                    .timeout(DDG_TIMEOUT_MS)
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
            String deduped = dedupeParagraphs(content, MAX_DYNAMIC_DOC_CHARS);
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
