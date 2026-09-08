package com.gole.api.promotion.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gole.api.promotion.domain.exception.InvalidPromotionPostStateException;
import com.gole.api.promotion.domain.exception.SelfReviewNotAllowedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionPostTest {

    private static final Instant NOW = Instant.EPOCH;

    private PromotionPost draft() {
        return PromotionPost.draft("promo-1", PromotionChannel.THREADS, "새 기능 나왔습니다", List.of(), "author-1", NOW);
    }

    @Test
    void draftStartsInDraftStatus() {
        PromotionPost post = draft();
        assertThat(post.getStatus()).isEqualTo(PromotionPostStatus.DRAFT);
        assertThat(post.getAuthorId()).isEqualTo("author-1");
    }

    @Test
    void rejectsCaptionOver500Chars() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() ->
                        PromotionPost.draft("promo-1", PromotionChannel.THREADS, tooLong, List.of(), "author-1", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void happyPathTransitionsThroughAllStates() {
        PromotionPost post = draft();

        post.submitForReview(NOW.plusSeconds(1));
        assertThat(post.getStatus()).isEqualTo(PromotionPostStatus.PENDING_REVIEW);
        assertThat(post.getSubmittedAt()).isEqualTo(NOW.plusSeconds(1));

        post.approve("reviewer-1", NOW.plusSeconds(2));
        assertThat(post.getStatus()).isEqualTo(PromotionPostStatus.APPROVED);
        assertThat(post.getReviewerId()).isEqualTo("reviewer-1");
        assertThat(post.getReviewedAt()).isEqualTo(NOW.plusSeconds(2));

        post.markPublished("threads-post-1", NOW.plusSeconds(3));
        assertThat(post.getStatus()).isEqualTo(PromotionPostStatus.PUBLISHED);
        assertThat(post.getExternalPostId()).isEqualTo("threads-post-1");
        assertThat(post.getPublishedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void rejectReturnsToDraftWithReason() {
        PromotionPost post = draft();
        post.submitForReview(NOW);

        post.reject("reviewer-1", "오탈자 있음", NOW.plusSeconds(1));

        assertThat(post.getStatus()).isEqualTo(PromotionPostStatus.DRAFT);
        assertThat(post.getRejectionReason()).isEqualTo("오탈자 있음");
        assertThat(post.getReviewerId()).isEqualTo("reviewer-1");
    }

    @Test
    void authorCannotApproveOwnPost() {
        PromotionPost post = draft();
        post.submitForReview(NOW);

        assertThatThrownBy(() -> post.approve("author-1", NOW.plusSeconds(1)))
                .isInstanceOf(SelfReviewNotAllowedException.class);
    }

    @Test
    void authorCannotRejectOwnPost() {
        PromotionPost post = draft();
        post.submitForReview(NOW);

        assertThatThrownBy(() -> post.reject("author-1", "사유", NOW.plusSeconds(1)))
                .isInstanceOf(SelfReviewNotAllowedException.class);
    }

    @Test
    void submitOnlyAllowedFromDraft() {
        PromotionPost post = draft();
        post.submitForReview(NOW);

        assertThatThrownBy(() -> post.submitForReview(NOW.plusSeconds(1)))
                .isInstanceOf(InvalidPromotionPostStateException.class);
    }

    @Test
    void approveOnlyAllowedFromPendingReview() {
        PromotionPost post = draft();

        assertThatThrownBy(() -> post.approve("reviewer-1", NOW))
                .isInstanceOf(InvalidPromotionPostStateException.class);
    }

    @Test
    void publishOnlyAllowedFromApproved() {
        PromotionPost post = draft();
        post.submitForReview(NOW);

        assertThatThrownBy(() -> post.markPublished("threads-post-1", NOW.plusSeconds(1)))
                .isInstanceOf(InvalidPromotionPostStateException.class);
    }
}
