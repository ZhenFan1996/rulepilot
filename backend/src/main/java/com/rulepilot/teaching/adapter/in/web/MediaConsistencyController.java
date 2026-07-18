package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.MediaConsistencyService;
import com.rulepilot.teaching.domain.MediaConsistencyReport;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/media-consistency")
@Profile("!test")
public class MediaConsistencyController {

    private final MediaConsistencyService consistency;
    private final TeachingPlanOwnerGuard owners;

    public MediaConsistencyController(MediaConsistencyService consistency, TeachingPlanOwnerGuard owners) {
        this.consistency = consistency;
        this.owners = owners;
    }

    @GetMapping
    MediaConsistencyReport evaluate(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return consistency.evaluate(planId);
    }
}
