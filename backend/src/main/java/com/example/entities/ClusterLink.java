package com.example.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents a detected connectivity link between two Kubernetes clusters.
 * Links are discovered automatically by inspecting multi-cluster CRDs
 * (Submariner, Linkerd) — they are never declared manually.
 */
@Entity
@Table(name = "cluster_links")
public class ClusterLink {

    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_TESTING = "testing";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_cluster_id", nullable = false)
    private Long sourceClusterId;

    @Column(name = "target_cluster_id", nullable = false)
    private Long targetClusterId;

    /** Connectivity type inferred from the detector (e.g. "mesh"). */
    @Column(name = "link_type", nullable = false, length = 50)
    private String linkType;

    /** Which detector found this link: "submariner" | "linkerd". */
    @Column(length = 50)
    private String source;

    @Column(name = "last_test_status", length = 20)
    private String lastTestStatus = STATUS_UNKNOWN;

    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    @Column(name = "last_test_message", columnDefinition = "TEXT")
    private String lastTestMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters / Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSourceClusterId() { return sourceClusterId; }
    public void setSourceClusterId(Long sourceClusterId) { this.sourceClusterId = sourceClusterId; }

    public Long getTargetClusterId() { return targetClusterId; }
    public void setTargetClusterId(Long targetClusterId) { this.targetClusterId = targetClusterId; }

    public String getLinkType() { return linkType; }
    public void setLinkType(String linkType) { this.linkType = linkType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLastTestStatus() { return lastTestStatus; }
    public void setLastTestStatus(String lastTestStatus) { this.lastTestStatus = lastTestStatus; }

    public LocalDateTime getLastTestAt() { return lastTestAt; }
    public void setLastTestAt(LocalDateTime lastTestAt) { this.lastTestAt = lastTestAt; }

    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
