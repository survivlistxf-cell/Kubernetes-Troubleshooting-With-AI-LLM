package com.kdiag.server.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.kdiag.server.docs.index.ChunkRetriever;
import com.kdiag.server.docs.index.ElasticChunkRetriever;
import com.kdiag.server.docs.index.LuceneChunkIndex;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;

/**
 * Selects the active {@link ChunkRetriever} implementation based on
 * {@code kdiag.retrieval.engine} (default: {@code lucene}).
 *
 * <h3>Lucene mode (default)</h3>
 * The existing {@link LuceneChunkIndex} bean is exposed as the
 * {@code @Primary ChunkRetriever}. ElasticSearch is not contacted.
 *
 * <h3>Elastic mode</h3>
 * An {@link ElasticsearchClient} bean is created from {@code kdiag.elastic.uri}
 * and the {@link ElasticChunkRetriever} bean is exposed as the {@code @Primary}
 * retriever. Both Lucene and ES are available simultaneously, which enables
 * A/B comparison experiments.
 */
@Configuration
public class RetrievalEngineConfig {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalEngineConfig.class);

    // -------------------------------------------------------------------------
    // Lucene mode  (default — matchIfMissing=true)
    // -------------------------------------------------------------------------

    /**
     * In Lucene mode, expose the existing {@link LuceneChunkIndex} as the
     * primary {@link ChunkRetriever}.  No ElasticSearch client is created.
     */
    @Bean(name = "activeChunkRetriever")
    @Primary
    @ConditionalOnProperty(
            name = "kdiag.retrieval.engine",
            havingValue = "lucene",
            matchIfMissing = true)
    public ChunkRetriever luceneChunkRetriever(LuceneChunkIndex luceneChunkIndex) {
        logger.info("Retrieval engine: LUCENE (BM25)");
        return luceneChunkIndex;
    }

    // -------------------------------------------------------------------------
    // Elastic mode
    // -------------------------------------------------------------------------

    /**
     * Creates the low-level {@link RestClient} → transport → {@link ElasticsearchClient}
     * chain when ES mode is active.  Uses the {@link JacksonJsonpMapper} so the
     * Jackson {@code ObjectMapper} already on the classpath handles all JSON.
     */
    @Bean
    @ConditionalOnProperty(name = "kdiag.retrieval.engine", havingValue = "elastic")
    public ElasticsearchClient elasticsearchClient(
            @Value("${kdiag.elastic.uri:http://localhost:9200}") String esUri) {
        try {
            URI uri = URI.create(esUri);
            String host   = uri.getHost();
            int    port   = uri.getPort() < 0 ? 9200 : uri.getPort();
            String scheme = uri.getScheme() != null ? uri.getScheme() : "http";

            RestClient httpClient = RestClient.builder(
                    new HttpHost(host, port, scheme)
            ).build();

            RestClientTransport transport = new RestClientTransport(
                    httpClient, new JacksonJsonpMapper()
            );
            logger.info("ElasticsearchClient created → {}", esUri);
            return new ElasticsearchClient(transport);
        } catch (Exception e) {
            logger.error("Failed to create ElasticsearchClient for {}: {}", esUri, e.getMessage());
            throw new RuntimeException("Cannot initialise ElasticsearchClient", e);
        }
    }

    /**
     * In ES mode, expose {@link ElasticChunkRetriever} as the primary retriever.
     * Lucene is still created ({@link LuceneChunkIndex} is always a Spring bean)
     * and remains usable for index-management operations and side-by-side comparison.
     */
    @Bean(name = "activeChunkRetriever")
    @Primary
    @ConditionalOnProperty(name = "kdiag.retrieval.engine", havingValue = "elastic")
    public ChunkRetriever elasticChunkRetriever(ElasticChunkRetriever elasticChunkRetriever) {
        logger.info("Retrieval engine: ELASTIC (hybrid BM25+kNN/RRF)");
        return elasticChunkRetriever;
    }
}
