package com.gole.api.promotion.application.port.in;

import com.gole.api.promotion.domain.model.PromotionPost;
import com.gole.api.promotion.domain.model.PromotionPostStatus;
import java.util.List;

/**
 * 홍보 게시물 검토·발행 유스케이스(관리자 전용). (promotion-review P3~P7)
 */
public interface ManagePromotionPostsUseCase {

    List<PromotionPost> list(PromotionPostStatus status, int limit);

    PromotionPost get(String promotionPostId);

    /** 작성자 본인이면 {@code SelfReviewNotAllowedException}. */
    PromotionPost approve(String promotionPostId, String reviewerId);

    /** 작성자 본인이면 {@code SelfReviewNotAllowedException}. */
    PromotionPost reject(String promotionPostId, String reviewerId, String reason);

    /** APPROVED만 발행 가능. 내부에서 {@code SocialPublishPort}를 호출한다. */
    PromotionPost publish(String promotionPostId);
}
