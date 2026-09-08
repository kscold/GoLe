package com.gole.api.operations.application.service;

import com.gole.api.common.exception.BadRequestException;
import com.gole.api.common.exception.ConflictException;
import com.gole.api.common.exception.ForbiddenException;
import com.gole.api.operations.application.port.out.OperationsDiagnostics;
import com.gole.api.operations.application.port.out.OperationsStore;
import com.gole.api.operations.domain.OperationRun;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
    public record Job(String id, String title, String description) {}

    public static final List<Job> JOBS = List.of(
            new Job("exception-queue", "예외큐 재집계", "현재 주문·배송 예외를 다시 조회합니다. 주문 상태를 변경하지 않습니다."),
            new Job("payment-readiness", "결제 준비상태 점검", "기존 결제 설정 검증을 실행합니다. 결제·송금 요청은 없습니다."),
            new Job("alert-readiness", "알림 연동 점검", "Discord 설정과 Sentry 준비 단계를 점검합니다. 외부 연결·발송은 없습니다."));
    private static final Set<String> REASONS = Set.of("MANUAL_CHECK", "INCIDENT_REVIEW", "RETRY_FAILED");
    private final OperationsStore store;
    private final OperationsDiagnostics diagnostics;
    private final Clock clock;

    public OperationsService(OperationsStore store, OperationsDiagnostics diagnostics, Clock clock) {
        this.store = store;
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    public List<OperationRun> history() {
        return store.recent();
    }

    public OperationRun execute(String jobId, String actorId, String reasonCode, String retryOf) {
        if (actorId == null || actorId.isBlank()) throw new ForbiddenException("ADMIN_ONLY", "관리자 인증이 필요합니다");
        if (JOBS.stream().noneMatch(job -> job.id().equals(jobId)))
            throw new BadRequestException("UNKNOWN_OPERATION", "허용되지 않은 작업입니다");
        if (reasonCode == null || !REASONS.contains(reasonCode))
            throw new BadRequestException("INVALID_REASON", "실행 사유 코드를 선택해 주세요");
        if (retryOf != null) {
            var previous = store.find(retryOf)
                    .orElseThrow(() -> new BadRequestException("INVALID_RETRY", "실패한 실행을 찾을 수 없습니다"));
            if (!previous.jobId().equals(jobId)
                    || !previous.status().equals("FAILED")
                    || !reasonCode.equals("RETRY_FAILED"))
                throw new BadRequestException("INVALID_RETRY", "같은 작업의 실패한 실행만 재시도할 수 있습니다");
        } else if (reasonCode.equals("RETRY_FAILED")) {
            throw new BadRequestException("INVALID_RETRY", "재시도할 실행을 선택해 주세요");
        }
        String id = UUID.randomUUID().toString();
        if (!store.acquire(jobId, id))
            throw new ConflictException("OPERATION_RUNNING", "이미 실행 중입니다. 미완료 실행은 운영자 확인이 필요합니다");
        OperationRun run =
                new OperationRun(id, jobId, actorId, reasonCode, retryOf, "RUNNING", Instant.now(clock), null, null);
        // Audit persistence must succeed before any job executes. Failed completion persistence retains the lock.
        try {
            store.save(run);
        } catch (RuntimeException failure) {
            store.release(jobId, id);
            throw failure;
        }
        OperationRun finished;
        try {
            finished = run.finish("SUCCEEDED", diagnostics.inspect(jobId), Instant.now(clock));
        } catch (RuntimeException failure) {
            finished = run.finish("FAILED", "DIAGNOSTIC_UNAVAILABLE", Instant.now(clock));
        }
        store.save(finished);
        store.release(jobId, id);
        return finished;
    }
}
