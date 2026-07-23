package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingDraftRejectionCategoryTest {

    @Test
    void keepsEvidencePolicyCodesStableForAuditActivities() {
        assertThat(TeachingDraftRejectionCategory.from(
                        new IllegalArgumentException("Evidence validation failed: CLAIM_WITHOUT_CITATION, VERSION_MISMATCH")))
                .isEqualTo("EVIDENCE_POLICY_CLAIM_WITHOUT_CITATION+VERSION_MISMATCH");
    }

    @Test
    void classifiesVisualAndUnknownFailuresWithoutExposingValidatorText() {
        assertThat(TeachingDraftRejectionCategory.from(
                        new IllegalArgumentException("VISUAL step needs an attached rulebook page")))
                .isEqualTo("VISUAL_PAGE_REQUIRED");
        assertThat(TeachingDraftRejectionCategory.from(new IllegalArgumentException("unexpected contract mismatch")))
                .isEqualTo("SCHEMA_OR_POLICY_INVALID");
    }
}
