package com.gole.api.promotion.domain.model;

/**
 * 홍보 게시물 상태. {@code DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED}이며 반려는
 * {@code DRAFT}로 되돌린다(promotion-review D2).
 */
public enum PromotionPostStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    PUBLISHED
}
