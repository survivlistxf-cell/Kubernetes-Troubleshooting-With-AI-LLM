package com.example.services;

import com.example.repositories.ChatAttachmentRepository;
import com.example.repositories.ConversationContextRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

@Component
public class RetentionCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(RetentionCleanupJob.class);

    // 30 days retention
    private static final int RETENTION_DAYS = 30;

    @Autowired
    private ChatAttachmentRepository chatAttachmentRepository;

    @Autowired
    private ConversationContextRepository conversationContextRepository;

    @Scheduled(cron = "0 30 3 * * *") // daily at 03:30
    public void cleanupOldRows() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        try {
            long deletedAtt = chatAttachmentRepository.deleteByCreatedAtBefore(cutoff);
            long deletedCtx = conversationContextRepository.deleteByCreatedAtBefore(cutoff);
            logger.info("Deleted attachments={}, contexts={}", deletedAtt, deletedCtx);
        } catch (Exception e) {
            logger.error("Cleanup failed", e);
        }
    }
}
