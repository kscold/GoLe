package com.gole.api.promotion.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 홍보 게시물 MongoDB 도큐먼트.
 */
@Document(collection = "promotion_posts")
public class PromotionPostDocument {

    @Id
    private String id;

    private String channel;
    private String caption;
    private List<String> mediaUrls;

    @Indexed
    private String authorId;

    @Indexed
    private String status;

    private Instant createdAt;
    private Instant submittedAt;
    private String reviewerId;
    private Instant reviewedAt;
    private String rejectionReason;
    private Instant publishedAt;
    private String externalPostId;

    protected PromotionPostDocument() {}

    public PromotionPostDocument(
            String id,
            String channel,
            String caption,
            List<String> mediaUrls,
            String authorId,
            String status,
            Instant createdAt,
            Instant submittedAt,
            String reviewerId,
            Instant reviewedAt,
            String rejectionReason,
            Instant publishedAt,
            String externalPostId) {
        this.id = id;
        this.channel = channel;
        this.caption = caption;
        this.mediaUrls = mediaUrls;
        this.authorId = authorId;
        this.status = status;
        this.createdAt = createdAt;
        this.submittedAt = submittedAt;
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = rejectionReason;
        this.publishedAt = publishedAt;
        this.externalPostId = externalPostId;
    }

    public String getId() {
        return id;
    }

    public String getChannel() {
        return channel;
    }

    public String getCaption() {
        return caption;
    }

    public List<String> getMediaUrls() {
        return mediaUrls;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getExternalPostId() {
        return externalPostId;
    }
}
