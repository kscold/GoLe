package com.gole.api.shipping.adapter.in.web;

import com.gole.api.admin.adapter.in.web.AdminActor;
import com.gole.api.shipping.application.port.in.ManageTrackerUseCase;
import com.gole.api.shipping.application.port.in.ManageTrackerUseCase.Sample;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.Diagnostics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** Existing /api/admin/** interceptor enforces ADMIN before any handler runs. */
@RestController
@RequestMapping("/api/admin/integrations/tracker")
public class TrackerAdminController {
    private final ManageTrackerUseCase tracker;

    public TrackerAdminController(ManageTrackerUseCase tracker) {
        this.tracker = tracker;
    }

    @GetMapping
    public Diagnostics status() {
        return tracker.status();
    }

    @PostMapping("/verify")
    public Diagnostics verify(HttpServletRequest http) {
        return tracker.verify(AdminActor.of(http).id());
    }

    @PostMapping("/sample")
    public Sample sample(@Valid @RequestBody SampleRequest request, HttpServletRequest http) {
        return tracker.sample(AdminActor.of(http).id(), request.carrier(), request.waybillNumber());
    }

    @PostMapping("/requery")
    public Sample refresh(@Valid @RequestBody RequeryRequest request, HttpServletRequest http) {
        return tracker.refresh(AdminActor.of(http).id(), request.orderId());
    }

    public record SampleRequest(
            @NotBlank @Size(max = 40) String carrier,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[0-9 -]+") String waybillNumber) {}

    public record RequeryRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,100}") String orderId) {}
}
