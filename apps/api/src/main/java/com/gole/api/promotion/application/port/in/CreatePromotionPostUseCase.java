package com.gole.api.promotion.application.port.in;

import com.gole.api.promotion.domain.model.PromotionChannel;
import java.util.List;

/**
 * 홍보 게시물 초안 작성 유스케이스. (promotion-review P1)
 */
public interface CreatePromotionPostUseCase {

    String create(CreatePromotionPostCommand command);

    /** @param mediaKeys 업로드 스테이지 키(예: {@code images/<uuid>.png}) 목록 — 공개 URL이 아니다.
     *  등록 시 {@code media} 컨텍스트로 PUBLIC 전이·연결한다(promotion-review D8). */
    record CreatePromotionPostCommand(String authorId, PromotionChannel channel, String caption, List<String> mediaKeys) {
        public CreatePromotionPostCommand {
            mediaKeys = mediaKeys == null ? List.of() : mediaKeys;
        }
    }
}
