package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.catalog.CatalogGamePresentationLookup.Presentation;
import com.rulepilot.teaching.application.TeachingPlanCatalogPresentationService;
import com.rulepilot.teaching.application.TeachingPlanService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/catalog-presentation")
@Profile("!test")
public class TeachingPlanCatalogPresentationController {

    private final TeachingPlanService plans;
    private final TeachingPlanCatalogPresentationService presentations;

    public TeachingPlanCatalogPresentationController(
            TeachingPlanService plans, TeachingPlanCatalogPresentationService presentations) {
        this.plans = plans;
        this.presentations = presentations;
    }

    @GetMapping
    ResponseEntity<Presentation> find(@PathVariable UUID planId, Principal principal) {
        var plan = plans.findOwned(planId, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "teaching plan does not exist"));
        return presentations.findForOwnedPlan(plan)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
