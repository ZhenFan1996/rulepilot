package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.LessonQualityService;
import com.rulepilot.teaching.domain.LessonQualityReport;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/illustrated-lessons/latest/quality")
@Profile("!test")
public class LessonQualityController {

    private final LessonQualityService quality;
    private final TeachingPlanOwnerGuard owners;

    public LessonQualityController(LessonQualityService quality, TeachingPlanOwnerGuard owners) {
        this.quality = quality;
        this.owners = owners;
    }

    @GetMapping
    LessonQualityReport evaluate(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return quality.evaluateLatest(planId);
    }
}
