package com.gole.api.promotion.application.port.in;

import com.gole.api.promotion.domain.model.PromotionPost;

/**
 * 초안을 검토 대기 상태로 넘기는 유스케이스. (promotion-review P2)
 */
public interface SubmitPromotionPostForReviewUseCase {

    PromotionPost submit(String promotionPostId);
}
