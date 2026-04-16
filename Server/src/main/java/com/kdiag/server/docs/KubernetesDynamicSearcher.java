package com.kdiag.server.docs;

import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.entities.ProblemResolution;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import com.kdiag.server.repositories.ProblemResolutionRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class KubernetesDynamicSearcher {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesDynamicSearcher.class);
    
    private final KubernetesDocPageRepository docRepository;
    private final ProblemResolutionRepository resolutionRepository;

    public KubernetesDynamicSearcher(KubernetesDocPageRepository docRepository, ProblemResolutionRepository resolutionRepository) {
        this.docRepository = docRepository;
        this.resolutionRepository = resolutionRepository;
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
                    .timeout(10000)
                    .get();

            // DDG HTML snippet URLs are stored inside a.result__url as raw text
            Elements resultAnchors = searchDoc.select("a.result__url");
            int count = 0;
            for (Element a : resultAnchors) {
                if (count >= 2) break; // Fetch top 2 results to avoid LLM context overflow
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
                        newContext.append("## Source: ").append(displayUrl).append("\n").append(text).append("\n\n");
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

        return newContext.toString();
    }

    private String fetchAndSaveDoc(String url) {
        Optional<KubernetesDocPage> existing = docRepository.findByUrl(url);
        if (existing.isPresent()) {
            logger.info("URL already in DB: {}", url);
            return truncate(existing.get().getTextContent(), 15000);
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; Kubexplain/1.0)")
                    .timeout(8000)
                    .get();
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
            String content = sb.toString().trim();
            String title = doc.title();
            if (title == null || title.isBlank()) title = "Discovered Kubernetes Doc";

            KubernetesDocPage page = new KubernetesDocPage(url, title, truncate(content, 20000), true);
            docRepository.save(page);

            return truncate(content, 15000);
        } catch (Exception e) {
            logger.error("Failed to fetch {}", url, e);
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
