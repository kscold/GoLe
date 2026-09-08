package com.gole.api.promotion.adapter.out.social;

import com.gole.api.promotion.application.port.out.SocialPublishPort;
import com.gole.api.promotion.domain.model.PromotionPost;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Threads 발행 어댑터의 스텁 구현. (promotion-review D5)
 *
 * <p><b>실제 Threads Graph API를 호출하지 않는다.</b> 저장소에 Threads 개발자 앱 자격증명이
 * 준비되기 전까지, 승인된 게시물의 "발행"은 로그만 남기고 모의 {@code externalPostId}를
 * 반환한다. 자격증명이 준비되면 이 클래스를 실제 어댑터로 교체한다 — 도메인·컨트롤러는 변경
 * 불필요(포트 뒤에 숨어 있음).
 */
@Component
public class StubThreadsPublishAdapter implements SocialPublishPort {

    private static final Logger log = LoggerFactory.getLogger(StubThreadsPublishAdapter.class);

    @Override
    public PublishResult publish(PromotionPost promotionPost) {
        String externalPostId = "stub-" + UUID.randomUUID();
        log.warn(
                "[STUB] Threads 실제 업로드 없이 모의 발행함. promotionPostId={}, channel={}, externalPostId={} "
                        + "— 실 연동 전까지 외부에 실제로 게시되지 않는다.",
                promotionPost.getId(),
                promotionPost.getChannel(),
                externalPostId);
        return new PublishResult(externalPostId);
    }
}
