package com.kdiag.server.docs.index;

import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.kdiag.server.metrics.MetricsCollector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LuceneChunkIndex {

    private static final Logger logger = LoggerFactory.getLogger(LuceneChunkIndex.class);

    @Value("${kdiag.lucene.dir:./lucene_index}")
    private String indexDir;

    private final KubernetesDocPageRepository repository;
    private final MetricsCollector metrics;

    private MMapDirectory directory;    // unde locuiește indexul pe disc
    private StandardAnalyzer analyzer;  // cum tokenizează textul (split + lowercase)
    private IndexWriter writer;         // pentru scrieri
    private SearcherManager searcherManager;    // pentru citiri thread-safe

    private volatile Instant lastRebuild;

    // Serialises all writes so search threads never see a half-committed index
    private final Object writeLock = new Object();

    public LuceneChunkIndex(KubernetesDocPageRepository repository, MetricsCollector metrics) {
        this.repository = repository;
        this.metrics    = metrics;
    }

    @PostConstruct
    public void init() {
        try {
            Path indexPath = Paths.get(indexDir);
            Files.createDirectories(indexPath);
            directory = new MMapDirectory(indexPath);
            analyzer = new StandardAnalyzer();

            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setSimilarity(new BM25Similarity())
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            writer = new IndexWriter(directory, config);
            searcherManager = new SearcherManager(writer, new SearcherFactory());

            logger.info("Lucene index opened at {}", indexPath.toAbsolutePath());

            int numDocs = writer.getDocStats().numDocs;
            if (numDocs == 0) {
                logger.info("Lucene index is empty — rebuilding from DB...");
                rebuildAll();
            } else {
                logger.info("Lucene index contains {} docs — running orphan-chunk GC...", numDocs);
                try {
                    garbageCollectOrphans();
                } catch (Exception gcEx) {
                    logger.warn("Orphan-chunk GC failed at startup (non-fatal): {}", gcEx.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to initialise Lucene index: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void close() {
        try { if (searcherManager != null) searcherManager.close(); } catch (Exception ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (directory != null) directory.close(); } catch (Exception ignored) {}
        logger.info("Lucene index closed");
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    public void indexPage(KubernetesDocPage page) {
        if (writer == null || page == null || page.getId() == null
                || page.getTextContent() == null || page.getTextContent().isBlank()) {
            return;
        }
        synchronized (writeLock) {
            try {
                // Remove previous chunks for this page
                writer.deleteDocuments(new Term("pageId", String.valueOf(page.getId())));

                List<String> chunks = ChunkSplitter.split(page.getTextContent());
                for (int i = 0; i < chunks.size(); i++) {
                    Document doc = new Document();
                    doc.add(new StringField("pageId",   String.valueOf(page.getId()), Field.Store.YES));
                    doc.add(new StringField("url",      nvl(page.getUrl()),           Field.Store.YES));
                    doc.add(new TextField("title",      nvl(page.getTitle()),         Field.Store.YES));
                    doc.add(new StringField("chunkIdx", String.valueOf(i),             Field.Store.YES));
                    doc.add(new TextField("text",       chunks.get(i),               Field.Store.YES));
                    writer.addDocument(doc);
                }
                writer.commit();
                logger.info("Indexed {} chunks for page '{}'", chunks.size(), page.getUrl());
            } catch (IOException e) {
                logger.warn("Failed to index page '{}': {}", page.getUrl(), e.getMessage());
            }
        }
    }

    public void rebuildAll() {
        if (writer == null) return;
        logger.info("Starting full Lucene index rebuild...");

        synchronized (writeLock) {
            try {
                writer.deleteAll();
                writer.commit();
            } catch (IOException e) {
                logger.warn("Failed to clear Lucene index: {}", e.getMessage());
            }
        }

        List<KubernetesDocPage> pages = repository.findAll();
        int done = 0;
        for (KubernetesDocPage page : pages) {
            indexPage(page);
            done++;
            if (done % 10 == 0) logger.info("Rebuilt {}/{} pages in Lucene index", done, pages.size());
        }
        lastRebuild = Instant.now();
        logger.info("Lucene rebuild complete — {} pages, {} total chunks", pages.size(), getChunkCount());
    }

    // -------------------------------------------------------------------------
    // Orphan-chunk garbage collection
    // -------------------------------------------------------------------------

    /**
     * Removes Lucene chunks whose {@code pageId} no longer exists in the DB.
     *
     * <p>This can happen when the {@code kubernetes_doc_pages} table is truncated
     * externally (e.g. via pgAdmin) while the Lucene index on disk still holds the
     * old chunks.  The method is called once on startup (when the index is non-empty)
     * and is also exposed via {@link #forceGarbageCollect()} / {@code POST /v1/index/gc}.
     */
    private void garbageCollectOrphans() {
        if (writer == null || searcherManager == null) return;

        // Step 1 — collect valid pageIds currently in the DB
        Set<Long> alive = repository.findAll().stream()
                .map(KubernetesDocPage::getId)
                .collect(Collectors.toSet());

        // Step 2 — scan every Lucene doc; collect orphaned pageIds + chunk count
        Set<Long> orphanIds    = new HashSet<>();
        int       orphanChunks = 0;
        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                TopDocs all = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
                for (ScoreDoc sd : all.scoreDocs) {
                    Document doc = searcher.getIndexReader().storedFields().document(sd.doc);
                    String pidStr = doc.get("pageId");
                    if (pidStr == null) continue;
                    try {
                        long pid = Long.parseLong(pidStr);
                        if (!alive.contains(pid)) {
                            orphanIds.add(pid);
                            orphanChunks++;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            logger.warn("Orphan-chunk GC scan failed: {}", e.getMessage());
            return;
        }

        if (orphanIds.isEmpty()) {
            logger.info("Orphan-chunk GC: index is clean ({} live pageIds checked)", alive.size());
            return;
        }

        // Step 3 — delete orphaned chunks and commit
        synchronized (writeLock) {
            try {
                for (Long orphanId : orphanIds) {
                    writer.deleteDocuments(new Term("pageId", String.valueOf(orphanId)));
                }
                writer.commit();
            } catch (IOException e) {
                logger.warn("Orphan-chunk GC delete/commit failed: {}", e.getMessage());
                return;
            }
        }

        try {
            searcherManager.maybeRefresh();
        } catch (Exception ignored) {}

        logger.info("Orphan-chunk GC removed {} chunks across {} orphaned pageIds",
                orphanChunks, orphanIds.size());
    }

    /**
     * Runs {@link #garbageCollectOrphans()} and returns the chunk count after cleanup.
     * Exposed for the {@code POST /v1/index/gc} endpoint.
     */
    public int forceGarbageCollect() {
        garbageCollectOrphans();
        return getChunkCount();
    }

    // -------------------------------------------------------------------------
    // ScoredChunk — private value type used for post-ranking boost
    // -------------------------------------------------------------------------

    private record ScoredChunk(DocChunk chunk, float score) {}

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Returns the top-K matching {@link DocChunk}s ranked by BM25 score.
     */
    public List<DocChunk> search(String queryText, int topK) {
        List<DocChunk> result = searchScored(queryText, topK).stream()
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
        metrics.recordBm25Search(result.isEmpty(), false);
        return result;
    }

    /**
     * Boost-aware search: retrieves {@code topK * 2} BM25 candidates, multiplies
     * each chunk's score by {@code 1.5} when its URL is in {@code boostedUrls},
     * re-sorts by adjusted score, and trims to {@code topK}.
     *
     * <p>Falls back to plain {@link #search(String, int)} when {@code boostedUrls}
     * is {@code null} or empty.
     */
    public List<DocChunk> search(String queryText, int topK, Set<String> boostedUrls) {
        if (boostedUrls == null || boostedUrls.isEmpty()) {
            // Delegate — recordBm25Search is called inside search(String, int) with boosted=false
            return search(queryText, topK);
        }
        List<ScoredChunk> candidates = searchScored(queryText, topK * 2);
        List<ScoredChunk> adjusted = new ArrayList<>(candidates.size());
        for (ScoredChunk sc : candidates) {
            float multiplier = boostedUrls.contains(sc.chunk().url()) ? 1.5f : 1.0f;
            adjusted.add(new ScoredChunk(sc.chunk(), sc.score() * multiplier));
        }
        adjusted.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        List<DocChunk> results = adjusted.stream()
                .limit(topK)
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
        logger.info("BM25 boost-aware search '{}...': {} candidates → {} results ({} boosted URLs)",
                queryText.length() > 80 ? queryText.substring(0, 80) : queryText,
                candidates.size(), results.size(), boostedUrls.size());
        metrics.recordBm25Search(results.isEmpty(), true);
        return results;
    }

    /**
     * Core search implementation: acquires a thread-safe searcher, runs the BM25
     * query, and returns raw {@link ScoredChunk} pairs so callers can apply
     * post-ranking adjustments before trimming.
     */
    private List<ScoredChunk> searchScored(String queryText, int topK) {
        if (queryText == null || queryText.isBlank() || searcherManager == null) return List.of();
        try {
            searcherManager.maybeRefresh(); //verifică dacă writer-ul a comitat ceva nou; dacă da, reîncarcă reader-ul.
            IndexSearcher searcher = searcherManager.acquire(); //primește un IndexSearcher thread-safe care va fi „închis" în finally.
            try {
                Query query = parseQuery(queryText);
                if (query == null) return List.of();

                TopDocs topDocs = searcher.search(query, topK);
                List<ScoredChunk> results = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.getIndexReader().storedFields().document(sd.doc);
                    DocChunk chunk = new DocChunk(
                            Long.parseLong(doc.get("pageId")),
                            doc.get("url"),
                            doc.get("title"),
                            Integer.parseInt(doc.get("chunkIdx")),
                            doc.get("text")
                    );
                    results.add(new ScoredChunk(chunk, sd.score));
                }
                return results;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            logger.warn("Lucene search failed for '{}': {}", queryText, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    public int getChunkCount() {
        if (writer == null) return 0;
        try {
            return writer.getDocStats().numDocs;
        } catch (Exception e) {
            return 0;
        }
    }

    public long getIndexBytes() {
        if (directory == null) return 0L;
        long total = 0;
        try {
            for (String f : directory.listAll()) {
                try { total += directory.fileLength(f); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return total;
    }

    public Instant getLastRebuild() {
        return lastRebuild;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Query parseQuery(String queryText) {
        QueryParser parser = new QueryParser("text", analyzer);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        try {
            return parser.parse(queryText);
        } catch (ParseException e) {
            // Retry with escaping for special-char inputs
            try {
                return parser.parse(QueryParser.escape(queryText));
            } catch (ParseException e2) {
                logger.warn("Could not parse Lucene query '{}': {}", queryText, e2.getMessage());
                return null;
            }
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
