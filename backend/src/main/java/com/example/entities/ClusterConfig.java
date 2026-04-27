package com.example.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents a saved Kubernetes cluster connection configuration.
 * Each entry maps to a kubeconfig file on disk + metadata.
 */
@Entity
@Table(name = "cluster_configs")
public class ClusterConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short unique name, e.g. "production", "staging", "dev-openstack" */
    @Column(nullable = false, length = 100)
    private String name;

    /** User-friendly label shown in UI, e.g. "OpenStack Production Cluster" */
    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Absolute path on the server to the kubeconfig file */
    @Column(name = "kubeconfig_path", nullable = false, length = 500)
    private String kubeconfigPath;

    /** Optional: specific context inside a multi-context kubeconfig */
    @Column(name = "context_name", length = 255)
    private String contextName;

    /** Default namespace to use when scanning this cluster */
    @Column(name = "default_namespace", length = 100)
    private String defaultNamespace = "default";

    /** Whether this is the default cluster (only one should be true) */
    @Column(name = "is_default")
    private boolean isDefault = false;

    /** Whether this cluster config is active/enabled */
    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    // --- Getters & Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getKubeconfigPath() {
        return kubeconfigPath;
    }

    public void setKubeconfigPath(String kubeconfigPath) {
        this.kubeconfigPath = kubeconfigPath;
    }

    public String getContextName() {
        return contextName;
    }

    public void setContextName(String contextName) {
        this.contextName = contextName;
    }

    public String getDefaultNamespace() {
        return defaultNamespace;
    }

    public void setDefaultNamespace(String defaultNamespace) {
        this.defaultNamespace = defaultNamespace;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
