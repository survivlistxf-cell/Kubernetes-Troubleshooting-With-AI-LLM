package com.kdiag.server.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kubernetes_doc_pages")
public class KubernetesDocPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 1024)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String textContent;

    @Column(nullable = false)
    private boolean isDynamic;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastScraped;

    public KubernetesDocPage() {
    }

    public KubernetesDocPage(String url, String title, String textContent, boolean isDynamic) {
        this.url = url;
        this.title = title;
        this.textContent = textContent;
        this.isDynamic = isDynamic;
        this.createdAt = LocalDateTime.now();
        this.lastScraped = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public boolean isDynamic() {
        return isDynamic;
    }

    public void setDynamic(boolean dynamic) {
        isDynamic = dynamic;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastScraped() {
        return lastScraped;
    }

    public void setLastScraped(LocalDateTime lastScraped) {
        this.lastScraped = lastScraped;
    }
}
