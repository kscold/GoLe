package com.gole.api.promotion.application.port.in;

import com.gole.api.promotion.domain.model.PromotionChannel;
import java.util.List;

/**
 * 홍보 게시물 초안 작성 유스케이스. (promotion-review P1)
 */
public interface CreatePromotionPostUseCase {

    String create(CreatePromotionPostCommand command);

    record CreatePromotionPostCommand(
            String authorId, PromotionChannel channel, String caption, List<String> mediaUrls) {}
}
