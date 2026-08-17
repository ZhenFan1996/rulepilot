package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingDraftRecoveryPolicyTest {

    private final TeachingDraftRecoveryPolicy policy = new TeachingDraftRecoveryPolicy();

    @Test
    void permitsOnlyOneSchemaRepair() {
        assertThat(policy.maxRepairAttempts(false)).isEqualTo(1);
        assertThat(policy.maxRepairAttempts(true)).isEqualTo(1);
    }

    @Test
    void fallsBackFromVisualOnlyWhenCitedTextStillExists() {
        assertThat(policy.canFallbackToCitedText(true, false)).isTrue();
        assertThat(policy.canFallbackToCitedText(true, true)).isFalse();
        assertThat(policy.canFallbackToCitedText(false, false)).isFalse();
        assertThat(policy.shouldFallbackToCitedText(true, false, 1)).isTrue();
        assertThat(policy.shouldFallbackToCitedText(true, false, 0)).isFalse();
    }

    @Test
    void removesPageImagesWithoutChangingSemanticRequestState() {
        UUID chunkId = UUID.randomUUID();
        SectionRequest visual = new SectionRequest(
                "flow",
                "流程",
                "讲清流程",
                List.of("flow"),
                List.of(),
                List.of(new EvidenceInput(chunkId, "FLOW", "Flow", "Rule", 3, 3)),
                List.of(new PageImageInput(3, "image/png", new byte[] {1}, 100, 100)),
                List.of("rule intent"),
                "owner",
                "chapter scope");

        SectionRequest textOnly = policy.withoutPageImages(visual);

        assertThat(textOnly.pageImages()).isEmpty();
        assertThat(textOnly.requiredRuleIntents()).isEqualTo(visual.requiredRuleIntents());
        assertThat(textOnly.objective()).isEqualTo(visual.objective());
        assertThat(textOnly.evidence()).isEqualTo(visual.evidence());
    }

}
