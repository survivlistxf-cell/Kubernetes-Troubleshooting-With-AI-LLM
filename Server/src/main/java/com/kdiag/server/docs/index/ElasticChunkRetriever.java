package com.kdiag.server.docs.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kdiag.server.entities.KubernetesDocPage;
import com.kdiag.server.metrics.MetricsCollector;
import com.kdiag.server.llm.OllamaEmbeddingClient;
import com.kdiag.server.repositories.KubernetesDocPageRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ElasticSearch-backed {@link ChunkRetriever} that performs <em>hybrid BM25+kNN</em>
 * retrieval, fusing the two result lists with application-side Reciprocal Rank Fusion
 * (RRF) so it runs on the free Basic license (the native {@code rrf} retriever is Platinum).
 *
 * <h3>Index mapping</h3>
 * <pre>
 *   pageId    keyword
 *   url       keyword
 *   title     text
 *   chunkIdx  integer
 *   chunkText text (analyzer: english)
 *   embedding dense_vector(768, cosine, indexed)
 * </pre>
 *
 * <h3>Query strategy</h3>
 * <ul>
 *   <li>If an embedding is available for the query text: hybrid BM25 {@code match}
 *       query + {@code knn}, run as two separate searches and fused in application
 *       code via Reciprocal Rank Fusion (works on the free Basic license).</li>
 *   <li>If the Ollama embedding model is unavailable: BM25-only {@code match}.</li>
 * </ul>
 *
 * <p>Activated only when {@code kdiag.retrieval.engine=elastic}.
 */
@Component
@ConditionalOnProperty(name = "kdiag.retrieval.engine", havingValue = "elastic")
public class ElasticChunkRetriever implements ChunkRetriever {

    private static final Logger logger = LoggerFactory.getLogger(ElasticChunkRetriever.class);

    private final ElasticsearchClient esClient;
    private final OllamaEmbeddingClient embeddingClient;
    private final KubernetesDocPageRepository pageRepository;
    private final MetricsCollector metrics;

    @Value("${kdiag.elastic.index:kdiag-chunks}")
    private String indexName;

    @Value("${kdiag.retrieval.topk:12}")
    private int defaultTopK;

    /**
     * Relevance gate over the kNN (semantic) leg of the hybrid search. ES scores cosine
     * kNN hits as {@code (1 + cos) / 2}, i.e. in [0..1] and comparable across queries —
     * unlike the RRF fusion scores (rank-based) or BM25 scores (unbounded), which is why
     * the gate is applied to the raw kNN top hit, before fusion. When the best kNN score
     * is below the gate, the whole result set is treated as irrelevant and discarded, so
     * the LLM sees an empty documentation block and can trigger a [NEEDS_SEARCH:] pass.
     * 0.85 ≈ cosine 0.70 for nomic-embed-text. Runtime-tunable via
     * {@code POST /v1/config/min-relevance} (no restart), 0 disables the gate.
     */
    @Value("${kdiag.retrieval.min-relevance:0.85}")
    private volatile double minRelevance;

    /** RRF rank constant (k); 60 is the standard default also used by Elasticsearch's native rrf. */
    private static final int RRF_K = 60;
    /** Per-list rank window: how many hits to pull from each of the BM25 and kNN lists before fusing. */
    private static final int RRF_WINDOW = 50;

    private volatile Instant lastRebuild;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @Override
    public void setMinRelevance(double minRelevance) {
        this.minRelevance = minRelevance;
    }

    @Override
    public double getMinRelevance() {
        return minRelevance;
    }

    public ElasticChunkRetriever(ElasticsearchClient esClient,
                                 OllamaEmbeddingClient embeddingClient,
                                 KubernetesDocPageRepository pageRepository,
                                 MetricsCollector metrics) {
        this.esClient          = esClient;
        this.embeddingClient   = embeddingClient;
        this.pageRepository    = pageRepository;
        this.metrics           = metrics;
    }

    // -------------------------------------------------------------------------
    // Startup: create index if absent
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        if (esClient == null) return;
        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(r -> r.index(indexName))).value();
            if (!exists) {
                createIndex();
            } else {
                logger.info("ElasticSearch index '{}' already exists ({} docs)",
                        indexName, getChunkCount());
            }
        } catch (Exception e) {
            logger.error("ElasticSearch init failed (ES may not be running): {}", e.getMessage());
        }
    }

    private void createIndex() throws Exception {
        esClient.indices().create(r -> r
                .index(indexName)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                )
                .mappings(m -> m
                        .properties("pageId",    p -> p.keyword(k -> k))
                        .properties("url",       p -> p.keyword(k -> k))
                        .properties("title",     p -> p.text(t -> t))
                        .properties("chunkIdx",  p -> p.integer(iv -> iv))
                        .properties("chunkText", p -> p.text(t -> t.analyzer("english")))
                        .properties("embedding", p -> p.denseVector(dv -> dv
                                .dims(768)
                                .index(true)
                                .similarity("cosine")
                        ))
                )
        );
        logger.info("Created ElasticSearch index '{}' with hybrid mapping (BM25+kNN, 768-dim cosine)",
                indexName);
    }

    // =========================================================================
    // ChunkRetriever — write operations
    // =========================================================================

    @Override
    public void indexPage(KubernetesDocPage page) {
        if (esClient == null || page == null || page.getId() == null
                || page.getTextContent() == null || page.getTextContent().isBlank()) {
            return;
        }
        try {
            // Delete previous chunks for this page
            esClient.deleteByQuery(r -> r
                    .index(indexName)
                    .query(q -> q.term(t -> t.field("pageId").value(String.valueOf(page.getId()))))
            );

            List<String> chunks = ChunkSplitter.split(page.getTextContent());
            if (chunks.isEmpty()) return;

            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String docId     = page.getId() + "_" + i;
                float[] emb      = embeddingClient != null ? embeddingClient.embed(chunkText) : null;

                Map<String, Object> doc = buildDoc(page, i, chunkText, emb);
                final int chunkIdx = i;
                final Map<String, Object> docFinal = doc;
                bulk.operations(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(docId)
                        .document(docFinal)
                ));
            }

            var bulkResp = esClient.bulk(bulk.build());
            if (bulkResp.errors()) {
                long errCount = bulkResp.items().stream().filter(it -> it.error() != null).count();
                logger.warn("ES bulk-index for page '{}' had {} errors", page.getUrl(), errCount);
            } else {
                logger.info("ES indexed {} chunks for page '{}'", chunks.size(), page.getUrl());
            }
        } catch (Exception e) {
            logger.warn("ES indexPage failed for '{}': {}", page.getUrl(), e.getMessage());
        }
    }

    @Override
    public void rebuildAll() {
        if (esClient == null) return;
        logger.info("Starting full ES index rebuild...");
        try {
            esClient.deleteByQuery(r -> r
                    .index(indexName)
                    .query(q -> q.matchAll(ma -> ma))
            );
        } catch (Exception e) {
            logger.warn("ES: could not clear index before rebuild: {}", e.getMessage());
        }

        List<KubernetesDocPage> pages = pageRepository.findAll();
        int done = 0;
        for (KubernetesDocPage page : pages) {
            indexPage(page);
            done++;
            if (done % 10 == 0) logger.info("ES rebuild {}/{}", done, pages.size());
        }
        lastRebuild = Instant.now();
        logger.info("ES rebuild complete — {} pages, {} total chunks", pages.size(), getChunkCount());
    }

    // =========================================================================
    // ChunkRetriever — read operations
    // =========================================================================

    @Override
    public List<DocChunk> search(String queryText, int topK) {
        long start = System.currentTimeMillis();
        List<DocChunk> results = searchInternal(queryText, topK);
        metrics.recordRetrievalSearch("elastic", System.currentTimeMillis() - start, results.size());
        return results;
    }

    @Override
    public List<DocChunk> search(String queryText, int topK, Set<String> boostedUrls) {
        if (boostedUrls == null || boostedUrls.isEmpty()) {
            return search(queryText, topK);
        }
        long start = System.currentTimeMillis();
        // Retrieve twice as many candidates so boosting has room to re-rank
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
        metrics.recordRetrievalSearch("elastic", System.currentTimeMillis() - start, results.size());
        logger.info("ES boost-aware search '{}...': {} candidates → {} results ({} boosted URLs)",
                queryText.length() > 80 ? queryText.substring(0, 80) : queryText,
                candidates.size(), results.size(), boostedUrls.size());
        return results;
    }

    // =========================================================================
    // ChunkRetriever — maintenance
    // =========================================================================

    @Override
    public int forceGarbageCollect() {
        if (esClient == null) return 0;
        try {
            // Collect all valid page IDs from DB
            List<FieldValue> aliveValues = pageRepository.findAll().stream()
                    .map(p -> FieldValue.of(String.valueOf(p.getId())))
                    .toList();

            if (aliveValues.isEmpty()) {
                // All docs are orphans — delete everything
                esClient.deleteByQuery(r -> r
                        .index(indexName)
                        .query(q -> q.matchAll(ma -> ma))
                );
            } else {
                // Delete docs whose pageId is NOT in the alive set
                esClient.deleteByQuery(r -> r
                        .index(indexName)
                        .query(q -> q.bool(b -> b
                                .mustNot(mn -> mn.terms(t -> t
                                        .field("pageId")
                                        .terms(tv -> tv.value(aliveValues))
                                ))
                        ))
                );
            }
            logger.info("ES GC: cleaned orphan chunks");
        } catch (Exception e) {
            logger.warn("ES forceGarbageCollect failed: {}", e.getMessage());
        }
        return getChunkCount();
    }

    // =========================================================================
    // ChunkRetriever — stats
    // =========================================================================

    @Override
    public int getChunkCount() {
        if (esClient == null) return 0;
        try {
            return (int) esClient.count(r -> r.index(indexName)).count();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long getIndexBytes() {
        if (esClient == null) return 0L;
        try {
            var stats = esClient.indices().stats(r -> r.index(indexName));
            var indexStats = stats.indices().get(indexName);
            if (indexStats == null) return 0L;
            var total = indexStats.total();
            if (total == null || total.store() == null) return 0L;
            Long bytes = total.store().sizeInBytes();
            return bytes != null ? bytes : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public Instant getLastRebuild() {
        return lastRebuild;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Value type for post-ranking boost. */
    record ScoredChunk(DocChunk chunk, float score) {}

    /**
     * Package-private for testing via subclass override.
     */
    List<ScoredChunk> searchScored(String queryText, int topK) {
        if (queryText == null || queryText.isBlank() || esClient == null) return List.of();
        try {
            float[] embedding  = embeddingClient != null ? embeddingClient.embed(queryText) : null;
            List<Float> embList = embedding != null ? toFloatList(embedding) : null;

            if (embList != null) {
                // Hybrid BM25 + kNN combined with Reciprocal Rank Fusion (RRF) computed in
                // application code. The native ES `rrf` retriever requires a Platinum/Enterprise
                // license and is rejected on the free Basic license (security_exception). Running
                // the two queries separately and fusing their ranks here (score = Σ 1/(RRF_K + rank))
                // keeps hybrid retrieval working without any paid license.
                final int window = Math.max(topK, RRF_WINDOW);
                final List<Float> embListFinal = embList;

                SearchResponse<ObjectNode> bm25 = esClient.search(s -> s
                        .index(indexName)
                        .size(window)
                        .query(q -> q.match(m -> m.field("chunkText").query(queryText))),
                        ObjectNode.class
                );

                SearchResponse<ObjectNode> knn = esClient.search(s -> s
                        .index(indexName)
                        .size(window)
                        .knn(k -> k
                                .field("embedding")
                                .queryVector(embListFinal)
                                .k((long) window)
                                .numCandidates((long) Math.max(window * 10, 100))
                        ),
                        ObjectNode.class
                );

                // Relevance gate: judge semantic relevance on the raw kNN score of the best
                // hit (normalized [0..1]), not on RRF/BM25 scores which are not comparable
                // across queries. Below the gate -> no usable context -> empty result, so
                // the model falls back to [NEEDS_SEARCH:] (dynamic mode) or general knowledge.
                double maxKnn = (!knn.hits().hits().isEmpty() && knn.hits().hits().get(0).score() != null)
                        ? knn.hits().hits().get(0).score() : 0.0;
                if (minRelevance > 0 && maxKnn < minRelevance) {
                    logger.info("Relevance gate: kNN top score {} < {} for '{}' — returning no context",
                            String.format(java.util.Locale.ROOT, "%.3f", maxKnn), minRelevance,
                            queryText.length() > 80 ? queryText.substring(0, 80) + "..." : queryText);
                    return List.of();
                }
                logger.info("Relevance gate: kNN top score {} (gate {})",
                        String.format(java.util.Locale.ROOT, "%.3f", maxKnn), minRelevance);

                return fuseRrf(List.of(bm25, knn), topK);
            }

            // BM25-only (Ollama embedding unavailable)
            SearchResponse<ObjectNode> response = esClient.search(s -> s
                    .index(indexName)
                    .size(topK)
                    .query(q -> q.match(m -> m.field("chunkText").query(queryText))),
                    ObjectNode.class
            );

            List<ScoredChunk> results = new ArrayList<>();
            for (Hit<ObjectNode> hit : response.hits().hits()) {
                DocChunk chunk = toChunk(hit.source());
                if (chunk == null) continue;
                double score = hit.score() != null ? hit.score() : 0.0;
                results.add(new ScoredChunk(chunk, (float) score));
            }
            return results;
        } catch (Exception e) {
            logger.warn("ES search failed for '{}': {}", queryText, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fuses several ranked result lists into one using Reciprocal Rank Fusion: for each
     * document, {@code score = Σ 1 / (RRF_K + rank)} over the lists in which it appears
     * (rank is 1-based). Documents are de-duplicated by their Elasticsearch {@code _id}.
     */
    private List<ScoredChunk> fuseRrf(List<SearchResponse<ObjectNode>> responses, int topK) {
        Map<String, Double> fusedScore = new HashMap<>();
        Map<String, DocChunk> byId = new HashMap<>();
        for (SearchResponse<ObjectNode> resp : responses) {
            int rank = 0;
            for (Hit<ObjectNode> hit : resp.hits().hits()) {
                rank++;
                String id = hit.id();
                if (id == null) continue;
                DocChunk chunk = toChunk(hit.source());
                if (chunk == null) continue;
                fusedScore.merge(id, 1.0 / (RRF_K + rank), Double::sum);
                byId.putIfAbsent(id, chunk);
            }
        }
        return fusedScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new ScoredChunk(byId.get(e.getKey()), e.getValue().floatValue()))
                .collect(Collectors.toList());
    }

    /** Maps an ES hit source document to a {@link DocChunk}, or {@code null} if absent. */
    private DocChunk toChunk(ObjectNode src) {
        if (src == null) return null;
        return new DocChunk(
                src.path("pageId").asLong(-1L),
                src.path("url").asText(""),
                src.path("title").asText(""),
                src.path("chunkIdx").asInt(0),
                src.path("chunkText").asText("")
        );
    }

    private List<DocChunk> searchInternal(String queryText, int topK) {
        return searchScored(queryText, topK).stream()
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
    }

    private static Map<String, Object> buildDoc(KubernetesDocPage page, int chunkIdx,
                                                 String chunkText, float[] embedding) {
        var doc = new java.util.LinkedHashMap<String, Object>();
        doc.put("pageId",    String.valueOf(page.getId()));
        doc.put("url",       page.getUrl()   != null ? page.getUrl()   : "");
        doc.put("title",     page.getTitle() != null ? page.getTitle() : "");
        doc.put("chunkIdx",  chunkIdx);
        doc.put("chunkText", chunkText);
        if (embedding != null) {
            doc.put("embedding", toFloatList(embedding));
        }
        return doc;
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
