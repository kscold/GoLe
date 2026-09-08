package com.gole.api.promotion.domain.model;

import com.gole.api.promotion.domain.exception.InvalidPromotionPostStateException;
import com.gole.api.promotion.domain.exception.SelfReviewNotAllowedException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 홍보 게시물 애그리거트 — Threads 등 외부 채널에 올릴 초안의 작성·검토·발행 상태 전이를
 * 캡슐화한다. 업로드 전 관리자 검토 게이트의 단일 진실 공급원(promotion-review).
 */
public final class PromotionPost {

    private static final int MAX_CAPTION_LENGTH = 500;

    private final String id;
    private final PromotionChannel channel;
    private final String caption;
    private final List<String> mediaUrls;
    private final String authorId;
    private PromotionPostStatus status;
    private final Instant createdAt;
    private Instant submittedAt;
    private String reviewerId;
    private Instant reviewedAt;
    private String rejectionReason;
    private Instant publishedAt;
    private String externalPostId;

    public PromotionPost(
            String id,
            PromotionChannel channel,
            String caption,
            List<String> mediaUrls,
            String authorId,
            PromotionPostStatus status,
            Instant createdAt,
            Instant submittedAt,
            String reviewerId,
            Instant reviewedAt,
            String rejectionReason,
            Instant publishedAt,
            String externalPostId) {
        this.id = Objects.requireNonNull(id, "id");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.caption = requireCaption(caption);
        this.mediaUrls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
        this.authorId = requireText(authorId, "authorId");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.submittedAt = submittedAt;
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = rejectionReason;
        this.publishedAt = publishedAt;
        this.externalPostId = externalPostId;
    }

    /** 신규 초안: DRAFT 상태로 생성. */
    public static PromotionPost draft(
            String id, PromotionChannel channel, String caption, List<String> mediaUrls, String authorId, Instant now) {
        return new PromotionPost(
                id,
                channel,
                caption,
                mediaUrls,
                authorId,
                PromotionPostStatus.DRAFT,
                now,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** 검토 요청: DRAFT → PENDING_REVIEW. DRAFT가 아니면 거부한다. */
    public void submitForReview(Instant now) {
        requireStatus(PromotionPostStatus.DRAFT);
        this.status = PromotionPostStatus.PENDING_REVIEW;
        this.submittedAt = Objects.requireNonNull(now, "now");
    }

    /** 승인: PENDING_REVIEW → APPROVED. 작성자 본인은 승인할 수 없다(D4). */
    public void approve(String reviewerId, Instant now) {
        requireStatus(PromotionPostStatus.PENDING_REVIEW);
        requireNotAuthor(reviewerId);
        this.status = PromotionPostStatus.APPROVED;
        this.reviewerId = reviewerId;
        this.reviewedAt = Objects.requireNonNull(now, "now");
    }

    /** 반려: PENDING_REVIEW → DRAFT. 사유를 남기고 작성자가 고쳐 다시 제출할 수 있게 한다. */
    public void reject(String reviewerId, String reason, Instant now) {
        requireStatus(PromotionPostStatus.PENDING_REVIEW);
        requireNotAuthor(reviewerId);
        this.status = PromotionPostStatus.DRAFT;
        this.reviewerId = reviewerId;
        this.reviewedAt = Objects.requireNonNull(now, "now");
        this.rejectionReason = requireText(reason, "reason");
    }

    /** 발행 완료 처리: APPROVED → PUBLISHED. 실제 호출은 SocialPublishPort가 담당하고, 여기서는 결과만 반영한다. */
    public void markPublished(String externalPostId, Instant now) {
        requireStatus(PromotionPostStatus.APPROVED);
        this.status = PromotionPostStatus.PUBLISHED;
        this.externalPostId = requireText(externalPostId, "externalPostId");
        this.publishedAt = Objects.requireNonNull(now, "now");
    }

    private void requireStatus(PromotionPostStatus expected) {
        if (status != expected) {
            throw new InvalidPromotionPostStateException(id, expected, status);
        }
    }

    private void requireNotAuthor(String reviewerId) {
        if (Objects.equals(authorId, reviewerId)) {
            throw new SelfReviewNotAllowedException(id);
        }
    }

    private static String requireCaption(String value) {
        String text = requireText(value, "caption");
        if (text.length() > MAX_CAPTION_LENGTH) {
            throw new IllegalArgumentException("caption must be at most " + MAX_CAPTION_LENGTH + " chars");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String getId() {
        return id;
    }

    public PromotionChannel getChannel() {
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

    public PromotionPostStatus getStatus() {
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
