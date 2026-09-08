package com.gole.api.promotion.application.port.out;

import com.gole.api.promotion.domain.model.PromotionPost;

/**
 * 외부 SNS 채널에 실제로 업로드하는 출력 포트. (promotion-review D5)
 *
 * <p>지금 유일한 구현체는 {@code StubThreadsPublishAdapter}로, 실제 Threads Graph API를 호출하지
 * 않고 모의 결과만 돌려준다. 자격증명이 준비되면 이 인터페이스를 구현하는 어댑터만 교체하면 된다.
 */
public interface SocialPublishPort {

    PublishResult publish(PromotionPost promotionPost);

    record PublishResult(String externalPostId) {}
}
