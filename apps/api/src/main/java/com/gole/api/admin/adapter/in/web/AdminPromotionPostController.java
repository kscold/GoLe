package com.gole.api.admin.adapter.in.web;

import com.gole.api.admin.application.port.in.RecordAdminActionUseCase;
import com.gole.api.admin.application.port.in.RecordAdminActionUseCase.RecordAdminActionCommand;
import com.gole.api.admin.domain.model.AdminActionType;
import com.gole.api.admin.domain.model.AdminTargetType;
import com.gole.api.promotion.application.port.in.CreatePromotionPostUseCase;
import com.gole.api.promotion.application.port.in.CreatePromotionPostUseCase.CreatePromotionPostCommand;
import com.gole.api.promotion.application.port.in.ManagePromotionPostsUseCase;
import com.gole.api.promotion.application.port.in.SubmitPromotionPostForReviewUseCase;
import com.gole.api.promotion.domain.model.PromotionChannel;
import com.gole.api.promotion.domain.model.PromotionPost;
import com.gole.api.promotion.domain.model.PromotionPostStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin · 홍보 게시 검토 — Threads 등 외부 채널 업로드 전 다른 관리자의 승인을 강제한다.
 * (promotion-review)
 *
 * <p>지금 발행(publish)은 {@code StubThreadsPublishAdapter}가 처리하며 실제 외부에 올라가지
 * 않는다(promotion-review D5) — 자격증명이 준비되기 전까지는 승인·발행을 아무리 눌러도 실제
 * Threads 계정에는 나가지 않는다.
 */
@Tag(name = "Admin · 홍보 게시 검토", description = "Threads 등 외부 채널 업로드 전 다른 관리자 승인 게이트")
@RestController
@RequestMapping("/api/admin/promotion-posts")
public class AdminPromotionPostController {

    private final CreatePromotionPostUseCase createPromotionPost;
    private final SubmitPromotionPostForReviewUseCase submitPromotionPost;
    private final ManagePromotionPostsUseCase managePromotionPosts;
    private final RecordAdminActionUseCase audit;

    public AdminPromotionPostController(
            CreatePromotionPostUseCase createPromotionPost,
            SubmitPromotionPostForReviewUseCase submitPromotionPost,
            ManagePromotionPostsUseCase managePromotionPosts,
            RecordAdminActionUseCase audit) {
        this.createPromotionPost = createPromotionPost;
        this.submitPromotionPost = submitPromotionPost;
        this.managePromotionPosts = managePromotionPosts;
        this.audit = audit;
    }

    @Operation(summary = "홍보 게시 초안 등록", description = "DRAFT 상태로 저장. 작성자는 요청한 관리자로 고정된다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@Valid @RequestBody CreatePromotionPostRequest request, HttpServletRequest http) {
        String id = createPromotionPost.create(new CreatePromotionPostCommand(
                AdminActor.of(http).id(), request.channel(), request.caption(), request.mediaUrls()));
        return Map.of("id", id);
    }

    @Operation(summary = "검토 요청", description = "DRAFT → PENDING_REVIEW. DRAFT가 아니면 거부된다.")
    @PostMapping("/{id}/submit")
    public PromotionPost submit(@PathVariable String id) {
        return submitPromotionPost.submit(id);
    }

    @Operation(summary = "홍보 게시 목록", description = "상태 필터 없으면 전체 최신순.")
    @GetMapping
    public List<PromotionPost> list(
            @RequestParam(required = false) PromotionPostStatus status, @RequestParam(defaultValue = "50") int limit) {
        return managePromotionPosts.list(status, limit);
    }

    @Operation(summary = "홍보 게시 단건 조회")
    @GetMapping("/{id}")
    public PromotionPost get(@PathVariable String id) {
        return managePromotionPosts.get(id);
    }

    @Operation(summary = "승인", description = "PENDING_REVIEW → APPROVED. 작성자 본인은 승인할 수 없다.")
    @PostMapping("/{id}/approve")
    public PromotionPost approve(@PathVariable String id, HttpServletRequest http) {
        AdminActor actor = AdminActor.of(http);
        PromotionPost approved = managePromotionPosts.approve(id, actor.id());
        record(http, AdminActionType.PROMOTION_POST_APPROVE, id, null);
        return approved;
    }

    @Operation(summary = "반려", description = "PENDING_REVIEW → DRAFT. 사유가 남고 작성자가 고쳐 재제출할 수 있다.")
    @PostMapping("/{id}/reject")
    public PromotionPost reject(
            @PathVariable String id, @Valid @RequestBody RejectPromotionPostRequest request, HttpServletRequest http) {
        AdminActor actor = AdminActor.of(http);
        PromotionPost rejected = managePromotionPosts.reject(id, actor.id(), request.reason());
        record(http, AdminActionType.PROMOTION_POST_REJECT, id, request.reason());
        return rejected;
    }

    @Operation(summary = "발행", description = "APPROVED → PUBLISHED. 지금은 스텁 어댑터가 처리해 실제 외부에 올라가지 않는다.")
    @PostMapping("/{id}/publish")
    public PromotionPost publish(@PathVariable String id, HttpServletRequest http) {
        PromotionPost published = managePromotionPosts.publish(id);
        record(http, AdminActionType.PROMOTION_POST_PUBLISH, id, published.getExternalPostId());
        return published;
    }

    private void record(HttpServletRequest http, AdminActionType type, String promotionPostId, String reason) {
        AdminActor actor = AdminActor.of(http);
        audit.record(new RecordAdminActionCommand(
                actor.id(), actor.email(), type, AdminTargetType.PROMOTION_POST, promotionPostId, reason));
    }

    public record CreatePromotionPostRequest(
            @NotNull PromotionChannel channel, @NotBlank @Size(max = 500) String caption, List<String> mediaUrls) {}

    public record RejectPromotionPostRequest(@NotBlank @Size(max = 1000) String reason) {}
}
