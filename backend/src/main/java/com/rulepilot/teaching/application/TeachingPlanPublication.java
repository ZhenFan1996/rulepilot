package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentTeachingPreparation;
import com.rulepilot.teaching.domain.TeachingPlan;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits the small persistence boundary after the rulebook has already been interpreted.
 *
 * <p>Model and vision calls must finish before this boundary begins: database transactions are for associating the
 * document with its game edition and publishing the resulting plan, not for waiting on providers.</p>
 */
@Service
@Profile("!test")
public class TeachingPlanPublication {

    private final DocumentTeachingPreparation documents;
    private final TeachingPlanRepository plans;

    public TeachingPlanPublication(DocumentTeachingPreparation documents, TeachingPlanRepository plans) {
        this.documents = documents;
        this.plans = plans;
    }

    @Transactional
    public TeachingPlan publish(TeachingPlan plan, String suggestedGameName) {
        documents.prepare(plan.documentVersionId(), plan.createdBy(), suggestedGameName);
        return plans.save(plan);
    }
}
