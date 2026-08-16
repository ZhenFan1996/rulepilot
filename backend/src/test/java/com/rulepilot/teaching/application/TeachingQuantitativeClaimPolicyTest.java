package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingQuantitativeClaimPolicyTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();

    @Test
    void rejectsANumberThatDoesNotExistInTheClaimsOwnCitedEvidence() {
        var evidence = evidence("There are six distinct scoring categories.");

        assertThatThrownBy(() -> validate(
                        List.of(new Claim(1, "计分：共有 7 个计分类别。", List.of(sourceId))),
                        evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported value", "7");
    }

    @Test
    void acceptsAnOpaqueDirectQuantityButRejectsAChangedValue() {
        var evidence = evidence("Each vek ledger records four luma in every nari row.");

        assertThatCode(() -> validate(
                        List.of(new Claim(1, "计分：每个 vek 账本在每个 nari 行记录 4 个 luma。", List.of(sourceId))),
                        evidence))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(
                        List.of(new Claim(1, "计分：每个 vek 账本在每个 nari 行记录 5 个 luma。", List.of(sourceId))),
                        evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported value", "5");
    }

    @Test
    void rejectsAUnitValueThatDropsAnExplicitSourceMultiplier() {
        var evidence = evidence(
                "Score one point for each eligible space on each matching card. Example: 2 x 9 = 18 points.");

        assertThatThrownBy(() -> validate(
                        List.of(new Claim(1, "计分：每个合格空间得 1 分。", List.of(sourceId))),
                        evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omits the source multiplier");
    }

    @Test
    void acceptsEitherAnExplicitFormulaOrTwoPreservedAggregationScopes() {
        var evidence = evidence(
                "Score one point for each eligible space on each matching card. Example: 2 x 9 = 18 points.");

        assertThatCode(() -> validate(
                        List.of(new Claim(1, "计分：对应卡数量 × 合格空间数量；例子是 2 × 9 = 18 分。", List.of(sourceId))),
                        evidence))
                .doesNotThrowAnyException();
        assertThatCode(() -> validate(
                        List.of(new Claim(1, "计分：每张对应卡都为每个合格空间计 1 分。", List.of(sourceId))),
                        evidence))
                .doesNotThrowAnyException();
    }

    @Test
    void keepsUnrelatedTurnOrdinalsOutOfTheQuantitativeRuleCheck() {
        var evidence = evidence("Take one action, then pass play clockwise.");

        assertThatCode(() -> validate(
                        List.of(new Claim(1, "步骤 3：选择一个行动，然后顺时针继续。", List.of(sourceId))),
                        evidence))
                .doesNotThrowAnyException();
    }

    private void validate(List<Claim> claims, RuleEvidence evidence) {
        var planned = new TeachingPlan.PlannedSection(
                1,
                "score",
                "计分",
                "Teach the complete scoring aggregation.",
                true,
                false,
                List.of("scoring aggregation"),
                List.of("scoring"));
        SectionDraft draft = new SectionDraft(
                "计分",
                VisualKind.SCOREBOARD,
                "按来源结算。",
                List.of(sourceId),
                List.of(new StepDraft("计分", TeachingMove.LEDGER, claims.getFirst().text(), List.of(sourceId))));
        TeachingQuantitativeClaimPolicy.validate(planned, draft, claims, Map.of(sourceId, evidence));
    }

    private RuleEvidence evidence(String excerpt) {
        return new RuleEvidence(sourceId, versionId, "SCORING", "Scoring", excerpt, 8, 8);
    }
}
