package com.gole.api.promotion.domain.exception;

import com.gole.api.common.exception.NotFoundException;

public class PromotionPostNotFoundException extends NotFoundException {

    public PromotionPostNotFoundException(String promotionPostId) {
        super("PROMOTION_POST_NOT_FOUND", "Promotion post not found: " + promotionPostId);
    }
}
