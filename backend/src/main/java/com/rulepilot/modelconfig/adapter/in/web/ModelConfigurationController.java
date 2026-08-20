package com.rulepilot.modelconfig.adapter.in.web;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Snapshot;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-configuration")
public class ModelConfigurationController {

    private final RuntimeModelConfiguration configuration;
    private final ModelAccountQuota quota;

    public ModelConfigurationController(RuntimeModelConfiguration configuration, ModelAccountQuota quota) {
        this.configuration = configuration;
        this.quota = quota;
    }

    @GetMapping
    Snapshot read(Principal principal) {
        return configuration.snapshot(principal.getName());
    }

    @GetMapping("/usage")
    ModelAccountQuota.AccountUsage usage(Principal principal) {
        LocalDate today = LocalDate.now(Clock.systemUTC());
        return quota.usage(principal.getName(), today.withDayOfMonth(1));
    }

    @PutMapping("/providers/{provider}")
    Snapshot configure(
            @PathVariable String provider, @RequestBody ConfigureProviderRequest request, Principal principal) {
        return configuration.configure(
                principal.getName(), provider, request.apiKey(), request.baseUrl(), request.model(),
                request.visionCapable());
    }

    @DeleteMapping("/providers/{provider}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    Snapshot disable(@PathVariable String provider, Principal principal) {
        return configuration.disable(principal.getName(), provider);
    }

    @PutMapping("/assignments")
    Snapshot assign(@RequestBody AssignmentsRequest request, Principal principal) {
        if (request.recommendation() == null || request.recommendation().isBlank()) {
            return configuration.assign(
                    principal.getName(),
                    request.teaching(),
                    request.visual(),
                    request.answer(),
                    request.critic());
        }
        return configuration.assign(
                principal.getName(),
                request.teaching(),
                request.visual(),
                request.answer(),
                request.critic(),
                request.recommendation());
    }

    record ConfigureProviderRequest(String apiKey, String baseUrl, String model, boolean visionCapable) {}

    record AssignmentsRequest(String teaching, String visual, String answer, String critic, String recommendation) {}
}
