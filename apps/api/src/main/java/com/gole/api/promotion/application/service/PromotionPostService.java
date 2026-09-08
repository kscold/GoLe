package com.gole.api.promotion.application.service;

import com.gole.api.media.application.port.in.ManageMediaAssetsUseCase;
import com.gole.api.media.domain.model.MediaKey;
import com.gole.api.media.domain.model.MediaTargetType;
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
    private final ManageMediaAssetsUseCase mediaAssets;
    private final Clock clock;

    public PromotionPostService(
            PromotionPostRepositoryPort repository,
            PromotionPostIdGeneratorPort idGenerator,
            SocialPublishPort publishPort,
            ManageMediaAssetsUseCase mediaAssets,
            Clock clock) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.publishPort = publishPort;
        this.mediaAssets = mediaAssets;
        this.clock = clock;
    }

    @Override
    public String create(CreatePromotionPostCommand command) {
        String id = idGenerator.newId();
        // media 컨텍스트의 인바운드 포트만 의존한다 — STAGED(업로더 전용, 24시간 뒤 폐기)를
        // 이 게시물에 연결해 PUBLIC으로 전이시키지 않으면, 검토자가 첨부 이미지를 못 보고
        // 하루 뒤 원본이 삭제된다(promotion-review D8).
        mediaAssets.replaceReferences(
                command.authorId(), MediaTargetType.PROMOTION_POST, id, command.mediaKeys(), true);
        List<String> mediaUrls = command.mediaKeys().stream().map(MediaKey::publicPath).toList();
        PromotionPost draft = PromotionPost.draft(
                id, command.channel(), command.caption(), mediaUrls, command.authorId(), Instant.now(clock));
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
