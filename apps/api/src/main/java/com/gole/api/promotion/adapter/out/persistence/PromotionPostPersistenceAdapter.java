package com.gole.api.promotion.adapter.out.persistence;

import com.gole.api.promotion.application.port.out.PromotionPostRepositoryPort;
import com.gole.api.promotion.domain.model.PromotionChannel;
import com.gole.api.promotion.domain.model.PromotionPost;
import com.gole.api.promotion.domain.model.PromotionPostStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 홍보 게시물 영속성 어댑터. 도메인 {@link PromotionPost}와 {@link PromotionPostDocument}를
 * 양방향 매핑한다.
 */
@Component
public class PromotionPostPersistenceAdapter implements PromotionPostRepositoryPort {

    private final PromotionPostMongoRepository repository;

    public PromotionPostPersistenceAdapter(PromotionPostMongoRepository repository) {
        this.repository = repository;
    }

    @Override
    public PromotionPost save(PromotionPost promotionPost) {
        return toDomain(repository.save(toDocument(promotionPost)));
    }

    @Override
    public Optional<PromotionPost> findById(String promotionPostId) {
        return repository.findById(promotionPostId).map(this::toDomain);
    }

    @Override
    public List<PromotionPost> findRecentFirst(PromotionPostStatus status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        List<PromotionPostDocument> documents = status == null
                ? repository.findAllByOrderByCreatedAtDesc(page)
                : repository.findByStatusOrderByCreatedAtDesc(status.name(), page);
        return documents.stream().map(this::toDomain).toList();
    }

    private PromotionPostDocument toDocument(PromotionPost promotionPost) {
        return new PromotionPostDocument(
                promotionPost.getId(),
                promotionPost.getChannel().name(),
                promotionPost.getCaption(),
                promotionPost.getMediaUrls(),
                promotionPost.getAuthorId(),
                promotionPost.getStatus().name(),
                promotionPost.getCreatedAt(),
                promotionPost.getSubmittedAt(),
                promotionPost.getReviewerId(),
                promotionPost.getReviewedAt(),
                promotionPost.getRejectionReason(),
                promotionPost.getPublishedAt(),
                promotionPost.getExternalPostId());
    }

    private PromotionPost toDomain(PromotionPostDocument document) {
        return new PromotionPost(
                document.getId(),
                PromotionChannel.valueOf(document.getChannel()),
                document.getCaption(),
                document.getMediaUrls(),
                document.getAuthorId(),
                PromotionPostStatus.valueOf(document.getStatus()),
                document.getCreatedAt(),
                document.getSubmittedAt(),
                document.getReviewerId(),
                document.getReviewedAt(),
                document.getRejectionReason(),
                document.getPublishedAt(),
                document.getExternalPostId());
    }
}
