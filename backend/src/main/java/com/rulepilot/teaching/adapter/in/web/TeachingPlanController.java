package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.TeachingPlanService;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/teaching-plans")
@Profile("!test")
public class TeachingPlanController {

    private final TeachingPlanService plans;

    public TeachingPlanController(TeachingPlanService plans) {
        this.plans = plans;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TeachingPlan create(@PathVariable UUID versionId, @RequestBody CreatePlanRequest request, Principal principal) {
        return plans.create(
                versionId,
                request.playerCount(),
                request.beginnerCount(),
                request.durationMinutes(),
                principal.getName());
    }

    @GetMapping("/latest")
    TeachingPlan latest(@PathVariable UUID versionId) {
        return plans.latest(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "teaching plan does not exist"));
    }

    record CreatePlanRequest(int playerCount, int beginnerCount, int durationMinutes) {}
}
