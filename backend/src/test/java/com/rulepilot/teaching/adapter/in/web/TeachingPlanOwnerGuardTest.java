package com.rulepilot.teaching.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.teaching.application.TeachingPlanService;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TeachingPlanOwnerGuardTest {

    private final TeachingPlanService plans = mock(TeachingPlanService.class);
    private final TeachingPlanOwnerGuard guard = new TeachingPlanOwnerGuard(plans);

    @Test
    void acceptsAnOwnedPlan() {
        var plan = plan("alice");
        when(plans.findOwned(plan.id(), "alice")).thenReturn(Optional.of(plan));

        guard.requireOwned(plan.id(), "alice");
    }

    @Test
    void returnsNotFoundForForeignPlansWithoutRevealingTheirExistence() {
        var planId = UUID.randomUUID();
        when(plans.findOwned(planId, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireOwned(planId, "alice"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private TeachingPlan plan(String owner) {
        return new TeachingPlan(
                UUID.randomUUID(), UUID.randomUUID(), 4, 2, 30, List.of(), owner, Instant.parse("2026-07-18T12:00:00Z"));
    }
}
