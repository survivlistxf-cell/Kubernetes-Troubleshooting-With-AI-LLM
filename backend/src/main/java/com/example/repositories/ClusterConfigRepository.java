package com.example.repositories;

import com.example.entities.ClusterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClusterConfigRepository extends JpaRepository<ClusterConfig, Long> {

    /** Find only active (enabled) clusters, ordered by default-first then name */
    List<ClusterConfig> findByIsActiveTrueOrderByIsDefaultDescNameAsc();

    /** Find the single default cluster */
    Optional<ClusterConfig> findByIsDefaultTrue();

    /** Check if a cluster with this name already exists */
    boolean existsByName(String name);

    /** Find by exact name */
    Optional<ClusterConfig> findByName(String name);
}
