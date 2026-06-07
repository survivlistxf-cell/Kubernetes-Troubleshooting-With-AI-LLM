package com.kdiag.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Ensures the {@code pgvector} extension exists in PostgreSQL <b>before</b> Hibernate
 * runs its schema update.
 *
 * <p>The {@code qa_feedback.embedding} column is declared as {@code vector(768)},
 * which fails with {@code type "vector" does not exist} unless the extension is
 * activated first.  In Docker / Kubernetes the {@code init.sql} entrypoint takes
 * care of this, but when developers run {@code mvnd spring-boot:run} against a
 * local Postgres instance the extension is typically not installed.
 *
 * <p>How it works:
 * <ol>
 *   <li>A {@link #pgVectorExtensionBootstrap(DataSource) pgVectorExtensionBootstrap}
 *       bean opens a JDBC connection and runs {@code CREATE EXTENSION IF NOT EXISTS vector}.</li>
 *   <li>The {@link BeanFactoryPostProcessor} re-writes the auto-configured
 *       {@code entityManagerFactory} bean definition to {@code @DependsOn} this
 *       bootstrap bean, so Hibernate's schema management runs only after the
 *       extension is in place.</li>
 * </ol>
 *
 * <p>Requires the host Postgres to have pgvector available (e.g. the
 * {@code pgvector/pgvector:pg16} image, or a superuser able to {@code CREATE
 * EXTENSION}).  If the call fails — pgvector binary missing, insufficient
 * privileges, etc. — we log a clear warning and let the application continue;
 * Hibernate will then surface the original "type vector does not exist" error
 * with the same level of detail as before.
 */
@Configuration
public class PgVectorExtensionInitializer implements BeanFactoryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PgVectorExtensionInitializer.class);

    /** Marker bean type so we can reference the bootstrap step by name. */
    public static final class PgVectorBootstrap {}

    @Bean
    public PgVectorBootstrap pgVectorExtensionBootstrap(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            // Pre-check pg_available_extensions so we DON'T issue a failing
            // CREATE EXTENSION when pgvector isn't compiled into this Postgres
            // — that throws SQLSTATE 0A000, which HikariCP logs at WARN level
            // and marks the connection as broken.  Polling the catalog is safe.
            boolean available;
            try (Statement check = conn.createStatement();
                 ResultSet rs = check.executeQuery(
                         "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'")) {
                available = rs.next();
            }

            if (!available) {
                // Tolerant fallback: qa_feedback.embedding is mapped as TEXT
                // (see QaFeedback javadoc) so the table still gets created.
                // Only the similarity-search queries in QaFeedbackRepository
                // are unavailable until pgvector is installed.
                logger.info("pgvector not available — feedback similarity search is DISABLED. " +
                        "App will run normally; install pgvector or switch to the " +
                        "pgvector/pgvector:pg16 image to enable it.");
                return new PgVectorBootstrap();
            }

            try (Statement create = conn.createStatement()) {
                create.execute("CREATE EXTENSION IF NOT EXISTS vector");
            }
            logger.info("pgvector extension ensured — feedback similarity search is ENABLED");
        } catch (SQLException e) {
            logger.warn("pgvector probe failed unexpectedly ({}). Assuming disabled.", e.getMessage());
        }
        return new PgVectorBootstrap();
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Force Hibernate's EntityManagerFactory to wait for pgVectorExtensionBootstrap
        // so the extension exists before Hibernate emits DDL for vector(...) columns.
        if (!beanFactory.containsBeanDefinition("entityManagerFactory")) {
            return;
        }
        BeanDefinition emfDef = beanFactory.getBeanDefinition("entityManagerFactory");
        String[] existing = emfDef.getDependsOn();
        String dep = "pgVectorExtensionBootstrap";
        if (existing == null || existing.length == 0) {
            emfDef.setDependsOn(dep);
            return;
        }
        for (String d : existing) {
            if (dep.equals(d)) return; // already wired
        }
        String[] updated = Arrays.copyOf(existing, existing.length + 1);
        updated[existing.length] = dep;
        emfDef.setDependsOn(updated);
    }
}
