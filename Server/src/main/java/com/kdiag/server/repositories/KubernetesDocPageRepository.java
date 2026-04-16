package com.kdiag.server.repositories;

import com.kdiag.server.entities.KubernetesDocPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KubernetesDocPageRepository extends JpaRepository<KubernetesDocPage, Long> {

    Optional<KubernetesDocPage> findByUrl(String url);

}
