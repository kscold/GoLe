package com.gole.api.promotion.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gole.api.promotion.application.port.in.CreatePromotionPostUseCase.CreatePromotionPostCommand;
import com.gole.api.promotion.application.port.out.PromotionPostIdGeneratorPort;
import com.gole.api.promotion.application.port.out.PromotionPostRepositoryPort;
import com.gole.api.promotion.application.port.out.SocialPublishPort;
import com.gole.api.promotion.application.port.out.SocialPublishPort.PublishResult;
import com.gole.api.promotion.domain.exception.InvalidPromotionPostStateException;
import com.gole.api.promotion.domain.exception.PromotionPostNotFoundException;
import com.gole.api.promotion.domain.model.PromotionChannel;
import com.gole.api.promotion.domain.model.PromotionPost;
import com.gole.api.promotion.domain.model.PromotionPostStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PromotionPostServiceTest {

    private final PromotionPostRepositoryPort repository = mock(PromotionPostRepositoryPort.class);
    private final PromotionPostIdGeneratorPort idGenerator = mock(PromotionPostIdGeneratorPort.class);
    private final SocialPublishPort publishPort = mock(SocialPublishPort.class);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final PromotionPostService service = new PromotionPostService(repository, idGenerator, publishPort, clock);

    private PromotionPost saved(PromotionPostStatus status, String authorId) {
        PromotionPost post =
                PromotionPost.draft("promo-1", PromotionChannel.THREADS, "캡션", List.of(), authorId, Instant.EPOCH);
        if (status != PromotionPostStatus.DRAFT) {
            post.submitForReview(Instant.EPOCH);
        }
        if (status == PromotionPostStatus.APPROVED || status == PromotionPostStatus.PUBLISHED) {
            post.approve("reviewer-1", Instant.EPOCH);
        }
        if (status == PromotionPostStatus.PUBLISHED) {
            post.markPublished("threads-1", Instant.EPOCH);
        }
        return post;
    }

    @Test
    void createSavesDraftAndReturnsId() {
        when(idGenerator.newId()).thenReturn("promo-1");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String id = service.create(
                new CreatePromotionPostCommand("author-1", PromotionChannel.THREADS, "새 기능 나왔습니다", List.of()));

        assertThat(id).isEqualTo("promo-1");
        ArgumentCaptor<PromotionPost> captor = ArgumentCaptor.forClass(PromotionPost.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PromotionPostStatus.DRAFT);
        assertThat(captor.getValue().getAuthorId()).isEqualTo("author-1");
    }

    @Test
    void publishChecksApprovedBeforeCallingPublishPort() {
        PromotionPost draft = saved(PromotionPostStatus.DRAFT, "author-1");
        when(repository.findById("promo-1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.publish("promo-1")).isInstanceOf(InvalidPromotionPostStateException.class);

        verify(publishPort, never()).publish(any());
    }

    @Test
    void publishCallsPublishPortAndStoresExternalPostId() {
        PromotionPost approved = saved(PromotionPostStatus.APPROVED, "author-1");
        when(repository.findById("promo-1")).thenReturn(Optional.of(approved));
        when(publishPort.publish(approved)).thenReturn(new PublishResult("stub-post-1"));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PromotionPost result = service.publish("promo-1");

        assertThat(result.getStatus()).isEqualTo(PromotionPostStatus.PUBLISHED);
        assertThat(result.getExternalPostId()).isEqualTo("stub-post-1");
    }

    @Test
    void approveRejectsSelfReview() {
        PromotionPost pending = saved(PromotionPostStatus.PENDING_REVIEW, "author-1");
        when(repository.findById("promo-1")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.approve("promo-1", "author-1"))
                .isInstanceOf(com.gole.api.promotion.domain.exception.SelfReviewNotAllowedException.class);
    }

    @Test
    void getThrowsWhenMissing() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(PromotionPostNotFoundException.class);
    }
}
