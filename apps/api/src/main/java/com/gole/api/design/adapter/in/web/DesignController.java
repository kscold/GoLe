package com.gole.api.design.adapter.in.web;

import com.gole.api.admin.adapter.in.web.AdminActor;
import com.gole.api.design.application.port.in.ManageDesignUseCase;
import com.gole.api.design.domain.model.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class DesignController {
    private final ManageDesignUseCase design;

    public DesignController(ManageDesignUseCase design) {
        this.design = design;
    }

    public record PublicTheme(long revision, Map<String, String> tokens) {}

    public record Editor(List<DesignSchema.Token> schema, DesignRevision current) {}

    public record Publish(
            @Min(0) long expectedRevision,
            @NotNull Map<String, String> tokens,
            @NotBlank @Size(max = 300) String reason) {}

    public record Restore(
            @Min(0) long expectedRevision, @Min(0) long sourceRevision, @NotBlank @Size(max = 300) String reason) {}

    @GetMapping("/api/v1/config/design")
    public ResponseEntity<PublicTheme> published() {
        var r = design.current();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PublicTheme(r.revision(), DesignSchema.validate(r.tokens())));
    }
    // Existing AdminWebConfig protects every /api/admin/** method with server-resolved ADMIN role.
    @GetMapping("/api/admin/design")
    public Editor editor() {
        return new Editor(DesignSchema.TOKENS, design.current());
    }

    @GetMapping("/api/admin/design/history")
    public List<DesignRevision> history(@RequestParam(defaultValue = "9223372036854775807") long before) {
        return design.history(before);
    }

    @PostMapping("/api/admin/design/publish")
    public DesignRevision publish(@Valid @RequestBody Publish body, HttpServletRequest request) {
        return design.publish(
                body.expectedRevision(),
                body.tokens(),
                body.reason(),
                AdminActor.of(request).id());
    }

    @PostMapping("/api/admin/design/restore")
    public DesignRevision restore(@Valid @RequestBody Restore body, HttpServletRequest request) {
        return design.restore(
                body.expectedRevision(),
                body.sourceRevision(),
                body.reason(),
                AdminActor.of(request).id());
    }
}
