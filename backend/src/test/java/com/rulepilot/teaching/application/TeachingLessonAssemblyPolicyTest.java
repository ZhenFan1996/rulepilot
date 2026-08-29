package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingLessonAssemblyPolicyTest {

    private final TeachingLessonAssemblyPolicy policy = new TeachingLessonAssemblyPolicy();

    @Test
    void distinguishesCompleteDraftAndIncompleteSnapshots() {
        TeachingPlan plan = plan();

        assertThat(policy.status(plan, List.of(section(1, "setup", EvidenceStatus.SUPPORTED, false),
                        section(2, "scoring", EvidenceStatus.SUPPORTED, false))))
                .isEqualTo(LessonStatus.COMPLETE);
        assertThat(policy.status(plan, List.of(section(1, "setup", EvidenceStatus.CITED_DRAFT, false),
                        section(2, "scoring", EvidenceStatus.SUPPORTED, false))))
                .isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(policy.status(plan, List.of(section(1, "setup", EvidenceStatus.SUPPORTED, false))))
                .isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(policy.snapshot(
                        UUID.randomUUID(),
                        plan,
                        List.of(section(1, "setup", EvidenceStatus.INSUFFICIENT_EVIDENCE, false),
                                section(2, "scoring", EvidenceStatus.SUPPORTED, false)),
                        "test-generator",
                        Instant.EPOCH).status())
                .isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(policy.snapshot(
                        UUID.randomUUID(),
                        plan,
                        List.of(section(1, "setup", EvidenceStatus.INSUFFICIENT_EVIDENCE, false),
                                section(2, "scoring", EvidenceStatus.INSUFFICIENT_EVIDENCE, false)),
                        "test-generator",
                        Instant.EPOCH).status())
                .isEqualTo(LessonStatus.INCOMPLETE);
    }

    @Test
    void reusesSupportedTextAndLetsOptionalVisualEnrichmentRunIndependently() {
        TeachingPlan plan = plan();
        LessonSection visualMissing = section(1, "setup", EvidenceStatus.SUPPORTED, false);
        LessonSection scoring = section(2, "scoring", EvidenceStatus.SUPPORTED, false);
        IllustratedLesson previous = new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                LessonStatus.COMPLETE,
                List.of(visualMissing, scoring, section(3, "old-topic", EvidenceStatus.SUPPORTED, true)),
                "reusable",
                Instant.EPOCH);

        Map<String, LessonSection> reusable = policy.reusableSections(
                plan, previous, Set.of("reusable"));

        assertThat(reusable).containsOnlyKeys("setup", "scoring");
        assertThat(policy.reusableSections(plan, previous, Set.of("other-version"))).isEmpty();
    }

    @Test
    void doesNotReuseLegacyModelOwnedVisualCoordinatesAfterTheOwnershipMigration() {
        TeachingPlan plan = plan();
        LessonSection legacyVisual = section(1, "setup", EvidenceStatus.SUPPORTED, true);
        IllustratedLesson previous = new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                LessonStatus.COMPLETE,
                List.of(legacyVisual),
                "adaptive-teaching-v58-whole-game-context",
                Instant.EPOCH);

        assertThat(legacyVisual.steps().getFirst().visualFocus()).isNotNull();
        assertThat(GroundedTeachingAgent.GENERATOR_VERSION)
                .isEqualTo("adaptive-teaching-v60-deterministic-publication");
        assertThat(policy.reusableSections(
                        plan,
                        previous,
                        Set.of(GroundedTeachingAgent.GENERATOR_VERSION)))
                .isEmpty();
    }

    @Test
    void keepsOnlyTheLastTwoSupportedSectionsForContinuityAndBuildsTheSafeFallback() {
        List<PriorSectionContext> context = policy.continuityContext(List.of(
                section(1, "setup", EvidenceStatus.SUPPORTED, false),
                section(2, "discarded", EvidenceStatus.INSUFFICIENT_EVIDENCE, false),
                section(3, "flow", EvidenceStatus.SUPPORTED, false),
                section(4, "scoring", EvidenceStatus.SUPPORTED, false)));
        LessonSection insufficient = policy.insufficient(plan().sections().getFirst());

        assertThat(context).extracting(PriorSectionContext::topicKey).containsExactly("flow", "scoring");
        assertThat(insufficient.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(insufficient.steps().getFirst())
                .extracting(LessonStep::kind, LessonStep::sourceChunkIds)
                .containsExactly(TeachingMove.WATCH, List.of());
    }

    private TeachingPlan plan() {
        return new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Test game",
                "Learn the rules",
                List.of(
                        planned(1, "setup", true),
                        planned(2, "scoring", false)),
                "owner",
                Instant.EPOCH);
    }

    private TeachingPlan.PlannedSection planned(int position, String topicKey, boolean visualRecommended) {
        return new TeachingPlan.PlannedSection(
                position,
                topicKey,
                topicKey + " title",
                "Learn " + topicKey,
                true,
                visualRecommended,
                List.of(topicKey),
                List.of(topicKey));
    }

    private LessonSection section(int position, String topicKey, EvidenceStatus status, boolean visual) {
        VisualFocus focus = visual ? new VisualFocus(1, "visible " + topicKey, 100, 100, 200, 200) : null;
        return new LessonSection(
                position,
                topicKey,
                List.of(topicKey),
                topicKey + " title",
                true,
                status,
                VisualKind.REFERENCE_CARD,
                "caption",
                List.of(new LessonStep(
                        1,
                        "step",
                        visual ? TeachingMove.VISUAL : TeachingMove.DO,
                        "Do " + topicKey,
                        List.of(1),
                        List.of(UUID.randomUUID()),
                        focus)));
    }
}
