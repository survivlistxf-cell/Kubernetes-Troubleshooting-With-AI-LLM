package com.kdiag.server.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "problem_resolutions")
public class ProblemResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String conversationId;

    @Column(nullable = false, length = 1000)
    private String searchQuery;

    @Column(columnDefinition = "TEXT")
    private String usefulUrls;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private Integer feedback = 0; // 0 = none, 1 = like, -1 = dislike

    public ProblemResolution() {
    }

    public ProblemResolution(String conversationId, String searchQuery, String usefulUrls) {
        this.conversationId = conversationId;
        this.searchQuery = searchQuery;
        this.usefulUrls = usefulUrls;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery;
    }

    public String getUsefulUrls() {
        return usefulUrls;
    }

    public void setUsefulUrls(String usefulUrls) {
        this.usefulUrls = usefulUrls;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getFeedback() {
        return feedback;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }
}
