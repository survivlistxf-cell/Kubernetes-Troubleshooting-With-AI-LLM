package com.example.repositories;

import com.example.entities.ClusterLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClusterLinkRepository extends JpaRepository<ClusterLink, Long> {

    List<ClusterLink> findAllByOrderByIdAsc();

    Optional<ClusterLink> findBySourceClusterIdAndTargetClusterId(Long sourceClusterId, Long targetClusterId);
}
