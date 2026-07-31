package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.teaching.application.RulebookIconGlossaryService.GlossaryStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Starts one administrator-selected public icon inventory without impersonating the lesson owner at the web edge.
 *
 * <p>The plan's persisted owner remains the model-configuration and run owner. This keeps evidence, budgets, and
 * cancellation in the same scope as the original lesson while allowing old public lessons to be maintained.</p>
 */
@Service
@Profile("!test")
public class PublicIconGlossaryBackfillService {

    private final PublicLessonReader publicLessons;
    private final TeachingPlanRepository plans;
    private final RulebookIconGlossaryService glossaries;
    private final IllustratedLessonLauncher launcher;

    public PublicIconGlossaryBackfillService(
            PublicLessonReader publicLessons,
            TeachingPlanRepository plans,
            RulebookIconGlossaryService glossaries,
            IllustratedLessonLauncher launcher) {
        this.publicLessons = publicLessons;
        this.plans = plans;
        this.glossaries = glossaries;
        this.launcher = launcher;
    }

    public Optional<BackfillLaunch> launch(UUID teachingPlanId) {
        if (publicLessons.find(teachingPlanId).isEmpty()) return Optional.empty();
        return plans.findById(teachingPlanId).map(this::launch);
    }

    private BackfillLaunch launch(TeachingPlan plan) {
        GlossaryStatus currentStatus = glossaries.viewPublic(plan.id()).status();
        if (currentStatus == GlossaryStatus.READY || currentStatus == GlossaryStatus.UNAVAILABLE) {
            return new BackfillLaunch(plan.id(), currentStatus, false, null, null, false);
        }

        var launch = launcher.prepareIconGlossary(plan.id(), plan.createdBy());
        return new BackfillLaunch(
                plan.id(),
                GlossaryStatus.GENERATING,
                !launch.reused(),
                launch.assistantRunId(),
                launch.state(),
                launch.reused());
    }

    public record BackfillLaunch(
            UUID teachingPlanId,
            GlossaryStatus status,
            boolean started,
            UUID assistantRunId,
            AssistantRunState runState,
            boolean reused) {

        public boolean accepted() {
            return status == GlossaryStatus.GENERATING;
        }
    }
}
