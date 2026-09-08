package com.gole.api.promotion.domain.exception;

import com.gole.api.common.exception.ForbiddenException;

/**
 * 작성자 본인이 자신의 초안을 승인·반려하려 할 때(promotion-review D4, 메이커-체커).
 */
public class SelfReviewNotAllowedException extends ForbiddenException {

    public SelfReviewNotAllowedException(String promotionPostId) {
        super("PROMOTION_POST_SELF_REVIEW_NOT_ALLOWED", "Author cannot review own promotion post: " + promotionPostId);
    }
}
