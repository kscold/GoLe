package com.gole.api.operations.adapter.in.web;

import com.gole.api.admin.adapter.in.web.AdminActor;
import com.gole.api.operations.application.service.OperationsService;
import com.gole.api.operations.domain.OperationRun;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Uses the existing /api/admin/** ADMIN interceptor and session/CSRF conventions. */
@RestController
@RequestMapping("/api/admin/operations")
public class OperationsController {
    private final OperationsService operations;

    public OperationsController(OperationsService operations) {
        this.operations = operations;
    }

    @GetMapping
    public Snapshot list() {
        return new Snapshot(OperationsService.JOBS, operations.history());
    }

    @PostMapping("/{jobId}/runs")
    public OperationRun execute(
            @PathVariable String jobId, @Valid @RequestBody ExecuteRequest request, HttpServletRequest http) {
        return operations.execute(jobId, AdminActor.of(http).id(), request.reasonCode(), request.retryOf());
    }

    public record ExecuteRequest(
            @NotBlank @Pattern(regexp = "MANUAL_CHECK|INCIDENT_REVIEW|RETRY_FAILED") String reasonCode,
            @Size(max = 36) String retryOf) {}

    public record Snapshot(List<OperationsService.Job> jobs, List<OperationRun> history) {}
}
