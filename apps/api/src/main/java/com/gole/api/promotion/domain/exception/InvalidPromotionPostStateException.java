package com.gole.api.promotion.domain.exception;

import com.gole.api.common.exception.ConflictException;
import com.gole.api.promotion.domain.model.PromotionPostStatus;

/**
 * 현재 상태에서 허용되지 않는 전이를 시도했을 때(promotion-review D2, P9).
 */
public class InvalidPromotionPostStateException extends ConflictException {

    public InvalidPromotionPostStateException(
            String promotionPostId, PromotionPostStatus expected, PromotionPostStatus actual) {
        super(
                "PROMOTION_POST_INVALID_STATE",
                "Promotion post " + promotionPostId + " expected " + expected + " but was " + actual);
    }
}
