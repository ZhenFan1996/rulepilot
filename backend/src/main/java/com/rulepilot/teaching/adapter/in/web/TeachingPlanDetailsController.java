package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.TeachingPlanService;
import com.rulepilot.teaching.application.TeachingPlanRemovalService;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teaching-plans")
@Profile("!test")
public class TeachingPlanDetailsController {

    private final TeachingPlanService plans;
    private final TeachingPlanRemovalService removals;

    public TeachingPlanDetailsController(TeachingPlanService plans, TeachingPlanRemovalService removals) {
        this.plans = plans;
        this.removals = removals;
    }

    @GetMapping
    List<TeachingPlan> list(Principal principal) {
        return plans.listOwned(principal.getName());
    }

    @GetMapping("/{planId}")
    TeachingPlan find(@PathVariable UUID planId, Principal principal) {
        return plans.findOwned(planId, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "teaching plan does not exist"));
    }

    @DeleteMapping("/{planId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID planId, Principal principal) {
        removals.removeOwned(planId, principal.getName());
    }

    @GetMapping("/cleanup-preview")
    TeachingPlanRemovalService.CleanupPreview cleanupPreview(Principal principal) {
        return removals.previewDuplicateCleanup(principal.getName());
    }

    @org.springframework.web.bind.annotation.PostMapping("/cleanup-duplicates")
    TeachingPlanRemovalService.CleanupResult cleanupDuplicates(Principal principal) {
        return removals.removeDuplicatePlans(principal.getName());
    }
}
