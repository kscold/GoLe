package com.gole.api.promotion.application.service;

import com.gole.api.promotion.application.port.in.CreatePromotionPostUseCase;
import com.gole.api.promotion.application.port.in.ManagePromotionPostsUseCase;
import com.gole.api.promotion.application.port.in.SubmitPromotionPostForReviewUseCase;
import com.gole.api.promotion.application.port.out.PromotionPostIdGeneratorPort;
import com.gole.api.promotion.application.port.out.PromotionPostRepositoryPort;
import com.gole.api.promotion.application.port.out.SocialPublishPort;
import com.gole.api.promotion.domain.exception.InvalidPromotionPostStateException;
import com.gole.api.promotion.domain.exception.PromotionPostNotFoundException;
import com.gole.api.promotion.domain.model.PromotionPost;
import com.gole.api.promotion.domain.model.PromotionPostStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 홍보 게시물 애플리케이션 서비스 — 작성, 검토 요청, 승인/반려, 발행을 오케스트레이션한다.
 * (promotion-review)
 */
@Service
public class PromotionPostService
        implements CreatePromotionPostUseCase, SubmitPromotionPostForReviewUseCase, ManagePromotionPostsUseCase {

    private final PromotionPostRepositoryPort repository;
    private final PromotionPostIdGeneratorPort idGenerator;
    private final SocialPublishPort publishPort;
    private final Clock clock;

    public PromotionPostService(
            PromotionPostRepositoryPort repository,
            PromotionPostIdGeneratorPort idGenerator,
            SocialPublishPort publishPort,
            Clock clock) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.publishPort = publishPort;
        this.clock = clock;
    }

    @Override
    public String create(CreatePromotionPostCommand command) {
        PromotionPost draft = PromotionPost.draft(
                idGenerator.newId(),
                command.channel(),
                command.caption(),
                command.mediaUrls(),
                command.authorId(),
                Instant.now(clock));
        return repository.save(draft).getId();
    }

    @Override
    public PromotionPost submit(String promotionPostId) {
        PromotionPost promotionPost = getOrThrow(promotionPostId);
        promotionPost.submitForReview(Instant.now(clock));
        return repository.save(promotionPost);
    }

    @Override
    public List<PromotionPost> list(PromotionPostStatus status, int limit) {
        return repository.findRecentFirst(status, limit);
    }

    @Override
    public PromotionPost get(String promotionPostId) {
        return getOrThrow(promotionPostId);
    }

    @Override
    public PromotionPost approve(String promotionPostId, String reviewerId) {
        PromotionPost promotionPost = getOrThrow(promotionPostId);
        promotionPost.approve(reviewerId, Instant.now(clock));
        return repository.save(promotionPost);
    }

    @Override
    public PromotionPost reject(String promotionPostId, String reviewerId, String reason) {
        PromotionPost promotionPost = getOrThrow(promotionPostId);
        promotionPost.reject(reviewerId, reason, Instant.now(clock));
        return repository.save(promotionPost);
    }

    @Override
    public PromotionPost publish(String promotionPostId) {
        PromotionPost promotionPost = getOrThrow(promotionPostId);
        // 상태를 먼저 확인해, 잘못된 상태에서 외부(SocialPublishPort) 호출이 나가지 않게 한다.
        if (promotionPost.getStatus() != PromotionPostStatus.APPROVED) {
            throw new InvalidPromotionPostStateException(
                    promotionPostId, PromotionPostStatus.APPROVED, promotionPost.getStatus());
        }
        SocialPublishPort.PublishResult result = publishPort.publish(promotionPost);
        promotionPost.markPublished(result.externalPostId(), Instant.now(clock));
        return repository.save(promotionPost);
    }

    private PromotionPost getOrThrow(String promotionPostId) {
        return repository
                .findById(promotionPostId)
                .orElseThrow(() -> new PromotionPostNotFoundException(promotionPostId));
    }
}
