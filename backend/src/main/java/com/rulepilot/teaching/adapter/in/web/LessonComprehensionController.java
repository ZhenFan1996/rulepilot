package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.LessonComprehensionService;
import com.rulepilot.teaching.domain.LessonComprehensionReport;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import com.rulepilot.teaching.domain.LessonComprehensionReport.VisualAidResult;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/teaching-plans/{planId}/comprehension")
public class LessonComprehensionController {

    private final LessonComprehensionService comprehension;
    private final TeachingPlanOwnerGuard owners;

    public LessonComprehensionController(
            LessonComprehensionService comprehension, TeachingPlanOwnerGuard owners) {
        this.comprehension = comprehension;
        this.owners = owners;
    }

    @GetMapping
    LessonComprehensionReport progress(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return comprehension.progress(planId, principal.getName());
    }

    @PutMapping("/{taskType}")
    LessonComprehensionReport record(
            @PathVariable UUID planId,
            @PathVariable TaskType taskType,
            @RequestBody ResultRequest request,
            Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return comprehension.record(planId, taskType, request.result(), principal.getName());
    }

    @PutMapping("/visual-aids/{visualAidKey}")
    LessonComprehensionReport recordVisualAid(
            @PathVariable UUID planId,
            @PathVariable String visualAidKey,
            @RequestBody VisualAidRequest request,
            Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return comprehension.recordVisualAid(planId, visualAidKey, request.result(), principal.getName());
    }

    record ResultRequest(PlayerResult result) {}

    record VisualAidRequest(VisualAidResult result) {}
}
