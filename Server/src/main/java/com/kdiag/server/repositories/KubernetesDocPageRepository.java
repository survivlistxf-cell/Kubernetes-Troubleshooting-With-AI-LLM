package com.kdiag.server.repositories;

import com.kdiag.server.entities.KubernetesDocPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface KubernetesDocPageRepository extends JpaRepository<KubernetesDocPage, Long> {

    Optional<KubernetesDocPage> findByUrl(String url);

    /**
     * Returns (id, url) pairs for dynamic pages older than {@code cutoff} that are NOT
     * referenced as useful in any positively-rated problem_resolutions row.
     * The useful_urls column stores newline-delimited URLs; unnest splits them per row.
     */
    @Query(value =
        "SELECT id, url FROM kubernetes_doc_pages " +
        "WHERE is_dynamic = true " +
        "  AND last_scraped < :cutoff " +
        "  AND url NOT IN ( " +
        "    SELECT DISTINCT trim(u) FROM problem_resolutions, " +
        "      unnest(string_to_array(useful_urls, E'\\n')) AS u " +
        "    WHERE feedback >= 1 AND useful_urls IS NOT NULL " +
        "  )",
        nativeQuery = true)
    List<Object[]> findStaleDynamicPages(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM kubernetes_doc_pages WHERE id IN :ids",
           nativeQuery = true)
    int deleteByIds(@Param("ids") List<Long> ids);
}
