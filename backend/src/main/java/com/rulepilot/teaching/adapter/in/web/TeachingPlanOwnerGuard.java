package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.TeachingPlanService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("!test")
class TeachingPlanOwnerGuard {

    private final TeachingPlanService plans;

    TeachingPlanOwnerGuard(TeachingPlanService plans) {
        this.plans = plans;
    }

    void requireOwned(UUID planId, String username) {
        if (plans.findOwned(planId, username).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "teaching plan does not exist");
        }
    }
}
