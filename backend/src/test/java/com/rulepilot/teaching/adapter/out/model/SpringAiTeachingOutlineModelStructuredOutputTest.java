package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringAiTeachingOutlineModelStructuredOutputTest {

    private static final String COMPACT_OUTLINE = """
            {
              "gameTitle":"示例游戏",
              "premise":"先选择行动，再结算行动结果。",
              "topics":[{
                "key":"take-action",
                "objective":"学会选择并结算一项行动",
                "required":true,
                "visualEvidenceRecommended":false,
                "teachingUnits":[{
                  "teachingUnitId":"choose-action",
                  "role":"LEGAL_ACTION",
                  "sourceSlotIds":["page-1-rule-1"]
                }]
              }],
              "wholeGameUnderstanding":{
                "summary":"玩家通过轮流选择行动推进游戏。",
                "concepts":[{
                  "conceptId":"action-choice",
                  "label":"行动选择",
                  "explanation":"每回合选择一项当前可用的行动。",
                  "sourceSlotIds":["page-1-rule-1"],
                  "relatedTopicKeys":["take-action"],
                  "prerequisiteConceptIds":[]
                }],
                "topicDependencies":[]
              }
            }
            """;

    @Test
    void admitsTheCompleteCompactPlanningEnvelope() throws Exception {
        var draft = SpringAiTeachingOutlineModel.parseCompactOutlineDraft(COMPACT_OUTLINE);

        assertThat(draft.gameTitle()).isEqualTo("示例游戏");
        assertThat(draft.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.objective()).isEqualTo("学会选择并结算一项行动");
            assertThat(topic.teachingUnits()).singleElement().satisfies(unit ->
                    assertThat(unit.sourceSlotIds()).containsExactly("page-1-rule-1"));
        });
        assertThat(draft.wholeGameUnderstanding().concepts())
                .singleElement()
                .satisfies(concept -> assertThat(concept.relatedTopicKeys()).containsExactly("take-action"));
    }

    @Test
    void rejectsMissingNestedFieldsUnknownFieldsDuplicateFieldsAndMarkdown() {
        String missingSourceSlotIds = """
                {
                  "gameTitle":"示例游戏",
                  "premise":"先选择行动，再结算行动结果。",
                  "topics":[{
                    "key":"take-action",
                    "objective":"学会选择并结算一项行动",
                    "required":true,
                    "visualEvidenceRecommended":false,
                    "teachingUnits":[{"teachingUnitId":"choose-action","role":"LEGAL_ACTION"}]
                  }],
                  "wholeGameUnderstanding":{
                    "summary":"玩家通过轮流选择行动推进游戏。",
                    "concepts":[],
                    "topicDependencies":[]
                  }
                }
                """;
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseCompactOutlineDraft(missingSourceSlotIds))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseCompactOutlineDraft(
                        COMPACT_OUTLINE.replace(
                                "\"objective\":\"学会选择并结算一项行动\",",
                                "\"objective\":\"学会选择并结算一项行动\",\"statusLine\":\"完成\",")))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseCompactOutlineDraft(
                        COMPACT_OUTLINE.replace(
                                "\"gameTitle\":\"示例游戏\",",
                                "\"gameTitle\":\"示例游戏\",\"gameTitle\":\"另一个游戏\",")))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseCompactOutlineDraft(
                        "```json\n" + COMPACT_OUTLINE + "\n```"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void appliesTheSameExactJsonBoundaryToLegacyOutlinesAndTargetedRepairs() throws Exception {
        String legacyOutline = """
                {
                  "gameTitle":"示例游戏",
                  "premise":"选择并结算行动。",
                  "topics":[],
                  "sourceCoverageSlots":[],
                  "sourceCoverageInventoryComplete":true,
                  "wholeGameUnderstanding":{
                    "summary":"选择并结算行动。",
                    "concepts":[],
                    "topicDependencies":[]
                  }
                }
                """;
        assertThat(SpringAiTeachingOutlineModel.parseOutlineDraft(legacyOutline).gameTitle())
                .isEqualTo("示例游戏");

        String ownershipPatch = """
                {"assignments":[{"sourceSlotId":"page-1-rule-1","teachingUnitId":"choose-action"}]}
                """;
        assertThat(SpringAiTeachingOutlineModel.parseMissingSlotOwnershipPatch(ownershipPatch).assignments())
                .singleElement()
                .satisfies(assignment -> assertThat(assignment.teachingUnitId()).isEqualTo("choose-action"));

        String contextPatch = """
                {
                  "concepts":[{
                    "conceptId":"action-choice",
                    "label":"行动选择",
                    "explanation":"每回合选择一项行动。",
                    "sourceSlotIds":["page-1-rule-1"],
                    "relatedTopicKeys":["take-action"],
                    "prerequisiteConceptIds":[]
                  }],
                  "topicDependencies":[]
                }
                """;
        assertThat(SpringAiTeachingOutlineModel.parseWholeGameContextPatch(contextPatch).concepts())
                .singleElement();

        String sourcePatch = """
                {"replacements":[{"slotId":"page-1-rule-1","sourceIdentifier":"Choose an action"}]}
                """;
        assertThat(SpringAiTeachingOutlineModel.parseSourceIdentifierPatches(sourcePatch).replacements())
                .singleElement()
                .satisfies(replacement -> assertThat(replacement.sourceIdentifier()).isEqualTo("Choose an action"));
    }

    @Test
    void derivesLegacyTopicAndConceptPagesFromTheirAuditableSourceSlots() {
        String identifier = "Place the board in the middle of the table.";
        OutlineDraft outline = new OutlineDraft(
                "示例游戏",
                "完成设置后开始行动。",
                List.of(new TopicDraft(
                        "setup", "开局设置", "完成开局设置", true, false,
                        List.of("setup"), List.of("setup", "source_coverage"), List.of(9))),
                List.of(new SourceCoverageSlotDraft(
                        "setup-board", SourceCoverageRole.SETUP, identifier, List.of(2),
                        "setup", "setup-board", SourceCoverageAvailability.SOURCED)),
                true,
                new WholeGameUnderstandingDraft(
                        "先设置桌面，再开始行动。",
                        List.of(new GlobalConceptDraft(
                                "setup-state", "开局状态", "开局设置决定初始桌面状态。",
                                List.of(identifier), List.of(9), List.of("setup"), List.of())),
                        List.of()));

        OutlineDraft bound = SpringAiTeachingOutlineModel.bindLegacySourceOwnership(outline);

        assertThat(bound.topics().getFirst().sourcePageNumbers()).containsExactly(2);
        assertThat(bound.wholeGameUnderstanding().concepts().getFirst().sourcePageNumbers()).containsExactly(2);
    }

    @Test
    void targetedRepairsCannotOmitTheirTypedCollectionsOrInventFields() {
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseMissingSlotOwnershipPatch("{}"))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseWholeGameContextPatch("{\"concepts\":[]}"))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseSourceIdentifierPatches(
                        "{\"replacements\":[],\"explanation\":\"fixed\"}"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void nullAndDuplicatePlanningArraysAreRejectedInsteadOfNormalized() {
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseCompactOutlineDraft(
                        COMPACT_OUTLINE.replace("\"sourceSlotIds\":[\"page-1-rule-1\"]",
                                "\"sourceSlotIds\":[\"page-1-rule-1\",\"page-1-rule-1\"]")))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseCompactOutlineDraft(
                        COMPACT_OUTLINE.replace("\"topicDependencies\":[]", "\"topicDependencies\":null")))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingOutlineModel.parseMissingSlotOwnershipPatch(
                        "{\"assignments\":[{\"sourceSlotId\":\"page-1-rule-1\",\"teachingUnitId\":\"choose-action\"},"
                                + "{\"sourceSlotId\":\"page-1-rule-1\",\"teachingUnitId\":\"choose-action\"}]}"))
                .isInstanceOf(JsonProcessingException.class);
    }
}
