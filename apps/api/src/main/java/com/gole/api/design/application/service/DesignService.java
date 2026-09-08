package com.gole.api.design.application.service;

import com.gole.api.common.exception.*;
import com.gole.api.design.application.port.in.ManageDesignUseCase;
import com.gole.api.design.application.port.out.DesignRepositoryPort;
import com.gole.api.design.domain.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DesignService implements ManageDesignUseCase {
    private final DesignRepositoryPort repository;

    public DesignService(DesignRepositoryPort repository) {
        this.repository = repository;
    }

    public DesignRevision current() {
        return repository.current();
    }

    public List<DesignRevision> history(long before) {
        return repository.history(before);
    }

    public DesignRevision publish(long expectedRevision, Map<String, String> tokens, String reason, String actorId) {
        return save(expectedRevision, tokens, reason, actorId, "PUBLISH");
    }

    public DesignRevision restore(long expectedRevision, long sourceRevision, String reason, String actorId) {
        Map<String, String> tokens = sourceRevision == 0
                ? DesignSchema.defaults()
                : repository
                        .find(sourceRevision)
                        .orElseThrow(() -> new BadRequestException("DESIGN_REVISION_NOT_FOUND", "복원할 이력이 없습니다"))
                        .tokens();
        return save(
                expectedRevision, tokens, reason, actorId, sourceRevision == 0 ? "RESET" : "RESTORE:" + sourceRevision);
    }

    private DesignRevision save(
            long expectedRevision, Map<String, String> tokens, String reason, String actorId, String action) {
        if (actorId == null || actorId.isBlank()) throw new ForbiddenException("ADMIN_ONLY", "관리자 권한이 필요합니다");
        if (reason == null || reason.isBlank() || reason.length() > 300)
            throw new BadRequestException("DESIGN_REASON_REQUIRED", "변경 사유를 1~300자로 입력해 주세요");
        var validated = DesignSchema.validate(tokens);
        if (expectedRevision < 0 || expectedRevision != current().revision())
            throw new ConflictException("DESIGN_REVISION_CONFLICT", "다른 관리자가 변경했습니다. 최신 값을 불러온 후 다시 검토해 주세요");
        var next = new DesignRevision(
                Math.addExact(expectedRevision, 1),
                validated,
                actorId,
                reason.trim(),
                action,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        repository.append(next);
        return next;
    }
}
