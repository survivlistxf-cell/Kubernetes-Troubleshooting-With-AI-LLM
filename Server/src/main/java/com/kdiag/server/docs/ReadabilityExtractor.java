package com.kdiag.server.docs;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

final class ReadabilityExtractor {
    private static final Logger log = LoggerFactory.getLogger(ReadabilityExtractor.class);

    private ReadabilityExtractor() {}

    /**
     * Returns a pair {title, plainText}. Falls back to legacyExtractor if Readability4j fails.
     */
    static Result extract(String url, Document jsoupDoc, Function<Document, String> legacyExtractor) {
        try {
            // String html = jsoupDoc.outerHtml();
            Readability4J r = new Readability4J(url, jsoupDoc);
            Article article = r.parse();
            String text = article != null ? article.getTextContent() : null;
            if (text == null || text.isBlank()) {
                log.warn("Readability4j returned empty text for {}, falling back to JSoup", url);
                return new Result(jsoupDoc.title(), legacyExtractor.apply(jsoupDoc));
            }
            String title = article.getTitle();
            if (title == null || title.isBlank()) title = jsoupDoc.title();
            log.info("Readability4j extracted {} chars from {}", text.length(), url);
            return new Result(title, text.trim());
        } catch (Exception e) {
            log.warn("Readability4j failed for {}: {}. Falling back to JSoup.", url, e.getMessage());
            return new Result(jsoupDoc.title(), legacyExtractor.apply(jsoupDoc));
        }
    }

    record Result(String title, String text) {}
}
