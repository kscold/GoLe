package com.gole.api.promotion.application.port.out;

import com.gole.api.promotion.domain.model.PromotionPost;
import com.gole.api.promotion.domain.model.PromotionPostStatus;
import java.util.List;
import java.util.Optional;

/**
 * 홍보 게시물 영속성 출력 포트.
 */
public interface PromotionPostRepositoryPort {

    PromotionPost save(PromotionPost promotionPost);

    Optional<PromotionPost> findById(String promotionPostId);

    List<PromotionPost> findRecentFirst(PromotionPostStatus status, int limit);
}
