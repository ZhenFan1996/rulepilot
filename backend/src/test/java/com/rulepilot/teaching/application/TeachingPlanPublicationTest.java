package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentTeachingPreparation;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

class TeachingPlanPublicationTest {

    @Test
    void preparesTheDocumentAndPublishesThePlanInOneShortPersistenceBoundary() {
        DocumentTeachingPreparation documents = mock(DocumentTeachingPreparation.class);
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        TeachingPlan plan = plan();
        when(plans.save(plan)).thenReturn(plan);

        TeachingPlan published = new TeachingPlanPublication(documents, plans).publish(plan, "Rulebook title");

        assertThat(published).isSameAs(plan);
        InOrder order = inOrder(documents, plans);
        order.verify(documents).prepare(plan.documentVersionId(), plan.createdBy(), "Rulebook title");
        order.verify(plans).save(plan);
    }

    @Test
    void keepsTheTransactionOnTheShortPublicationBoundary() throws NoSuchMethodException {
        Method publication = TeachingPlanPublication.class.getMethod("publish", TeachingPlan.class, String.class);
        Method creation = TeachingPlanService.class.getMethod(
                "create", UUID.class, int.class, int.class, int.class, String.class, UUID.class);

        assertThat(publication.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(creation.isAnnotationPresent(Transactional.class)).isFalse();
    }

    private TeachingPlan plan() {
        return new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                4,
                2,
                45,
                "Game",
                "Learn the core loop.",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "core-loop",
                        "Core loop",
                        "Take a turn.",
                        true,
                        false,
                        List.of("turn"),
                        List.of("core_loop"),
                        List.of(2))),
                "alice",
                Instant.now());
    }
}
