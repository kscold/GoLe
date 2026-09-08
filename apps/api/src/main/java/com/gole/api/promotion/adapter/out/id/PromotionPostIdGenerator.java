package com.gole.api.promotion.adapter.out.id;

import com.gole.api.promotion.application.port.out.PromotionPostIdGeneratorPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * UUID 기반 홍보 게시물 식별자 생성 어댑터.
 */
@Component
public class PromotionPostIdGenerator implements PromotionPostIdGeneratorPort {

    @Override
    public String newId() {
        return UUID.randomUUID().toString();
    }
}
