package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import org.junit.jupiter.api.Test;

class SpringAiContentCriticModelStructuredOutputTest {

    @Test
    void admitsTheTypedVerdictAspectClaimAndEvidenceBinding() {
        var draft = SpringAiContentCriticModel.parseStructuredDraft("""
                {"issues":[{
                  "defectConfirmed":true,
                  "type":"CONTRADICTION",
                  "claimAspect":"TIMING",
                  "claimPosition":2,
                  "evidenceIds":["E1"],
                  "summary":"该步骤把结算时点写早了。"
                }]}
                """);

        assertThat(draft.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.defectConfirmed()).isTrue();
            assertThat(issue.type()).isEqualTo(IssueType.CONTRADICTION);
            assertThat(issue.claimAspect()).isEqualTo(ClaimAspect.TIMING);
            assertThat(issue.claimPosition()).isEqualTo(2);
            assertThat(issue.evidenceIds()).containsExactly("E1");
        });
    }

    @Test
    void rejectsMissingCriticFieldsAndAcceptsAdditiveMetadata() {
        assertThatThrownBy(() -> SpringAiContentCriticModel.parseStructuredDraft("{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SpringAiContentCriticModel.parseStructuredDraft(
                        "{\"issues\":[],\"answer\":\"looks fine\"}").issues())
                .isEmpty();
        assertThatThrownBy(() -> SpringAiContentCriticModel.parseStructuredDraft("""
                        {"issues":[{
                          "defectConfirmed":true,
                          "type":"CONTRADICTION",
                          "claimPosition":2,
                          "evidenceIds":["E1"],
                          "summary":"missing claimAspect"
                        }]}
                        """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAndDuplicateCriticArraysInsteadOfNormalizingThem() {
        assertThatThrownBy(() -> SpringAiContentCriticModel.parseStructuredDraft("{\"issues\":null}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpringAiContentCriticModel.parseStructuredDraft("""
                        {"issues":[{
                          "defectConfirmed":true,
                          "type":"CONTRADICTION",
                          "claimAspect":"TIMING",
                          "claimPosition":2,
                          "evidenceIds":["E1","E1"],
                          "summary":"该步骤把结算时点写早了。"
                        }]}
                        """))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
