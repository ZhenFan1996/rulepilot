package com.rulepilot.modelconfig.adapter.in.web;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/model-configuration")
public class ModelConfigurationController {

    private final RuntimeModelConfiguration configuration;

    public ModelConfigurationController(RuntimeModelConfiguration configuration) {
        this.configuration = configuration;
    }

    @GetMapping
    Snapshot read() {
        return configuration.snapshot();
    }

    @PutMapping("/providers/{provider}")
    Snapshot configure(@PathVariable String provider, @RequestBody ConfigureProviderRequest request) {
        return configuration.configure(provider, request.apiKey(), request.baseUrl(), request.model());
    }

    @DeleteMapping("/providers/{provider}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    Snapshot disable(@PathVariable String provider) {
        return configuration.disable(provider);
    }

    @PutMapping("/assignments")
    Snapshot assign(@RequestBody AssignmentsRequest request) {
        return configuration.assign(request.teaching(), request.answer(), request.critic());
    }

    record ConfigureProviderRequest(String apiKey, String baseUrl, String model) {}

    record AssignmentsRequest(String teaching, String answer, String critic) {}
}
