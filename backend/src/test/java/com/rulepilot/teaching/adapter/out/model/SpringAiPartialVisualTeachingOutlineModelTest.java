package com.rulepilot.teaching.adapter.out.model;

import static com.rulepilot.teaching.VisualRuleGroupTestFacts.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.application.TeachingSourceCoverageContract;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class SpringAiPartialVisualTeachingOutlineModelTest {

    private static final String COMPACT_OUTLINE = """
            {
              "gameTitle":"不透明游戏",
              "premise":"先理解可验证的行动关系。",
              "topics":[{
                "key":"choose-action",
                "objective":"能够依据已验证规则选择行动。",
                "required":true,
                "visualEvidenceRecommended":false,
                "teachingUnits":[{
                  "teachingUnitId":"choose-action",
                  "role":"LEGAL_ACTION",
                  "sourceSlotIds":["page-1-rule-1"]
                }]
              }],
              "wholeGameUnderstanding":{
                "summary":"已验证页面说明了行动选择。",
                "concepts":[{
                  "conceptId":"action-choice",
                  "label":"行动选择",
                  "explanation":"玩家依照已验证规则选择行动。",
                  "sourceSlotIds":["page-1-rule-1"],
                  "relatedTopicKeys":["choose-action"],
                  "prerequisiteConceptIds":[]
                }],
                "topicDependencies":[]
              }
            }
            """;
    private static final String COMPACT_OUTLINE_WITH_PARTIAL_SLOT = COMPACT_OUTLINE.replace(
            "\"sourceSlotIds\":[\"page-1-rule-1\"]",
            "\"sourceSlotIds\":[\"page-1-rule-1\",\"page-2-rule-1\"]");

    @Test
    void plansExactVisualEvidenceWhileLocalizingAnExplicitlyUnavailablePage() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(COMPACT_OUTLINE));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            var outline = model.organize(partialVisualRequest());

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1");
            assertThat(outline.topics()).allSatisfy(topic -> assertThat(topic.coverageTags())
                    .contains(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));
            assertThat(outline.premise()).contains(
                    "第2页本轮没有获得可验证的视觉证据",
                    "不会推断这些页面未纳入的规则");

            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            List<String> instructions = prompt.getValue().getInstructions().stream()
                    .map(message -> message.getText())
                    .toList();
            assertThat(instructions).anySatisfy(text -> assertThat(text).contains(
                    "PAGE_LEDGER_STATE: VISUAL_EXACT_COMPLETE",
                    "PAGE_LEDGER_STATE: VISUAL_EXPLICITLY_UNAVAILABLE",
                    "source obligations for this page are unknown"));
            assertThat(instructions).allSatisfy(text -> assertThat(text).doesNotContain(
                    "UNSAFE_EXACT_DISPLAY_TEXT_1",
                    "UNSAFE_UNAVAILABLE_DISPLAY_TEXT"));
        } finally {
            model.close();
        }
    }

    @Test
    void plansExactlyBoundFactsFromAPartialPageWithoutSendingItsDisplaySummary() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(COMPACT_OUTLINE_WITH_PARTIAL_SLOT));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            var outline = model.organize(exactAndPartialRequest());

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1", "R-2");
            assertThat(outline.topics()).allSatisfy(topic -> assertThat(topic.coverageTags())
                    .contains(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));
            assertThat(outline.premise()).contains(
                    "第2页仅纳入了已精确绑定的视觉规则",
                    "未列出的来源义务仍未知");

            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            List<String> instructions = prompt.getValue().getInstructions().stream()
                    .map(message -> message.getText())
                    .toList();
            assertThat(instructions).anySatisfy(text -> assertThat(text).contains(
                    "PAGE_LEDGER_STATE: VISUAL_PARTIAL",
                    "SOURCE_INVENTORY: incomplete; unlisted source obligations are unknown",
                    "RULE_GROUP_IDENTIFIER: R-2",
                    "RULE_GROUP_FACT: Complete page-owned fact for R-2."));
            assertThat(instructions).anySatisfy(text -> assertThat(text).contains(
                    "VISUAL_PARTIAL supplied only the listed canonical slots and typed rule-group facts",
                    "unlisted source obligations remain unknown"));
            assertThat(instructions).noneSatisfy(text -> assertThat(text).contains(
                    "UNSAFE_PARTIAL_DISPLAY_TEXT"));
        } finally {
            model.close();
        }
    }

    @Test
    void plansAnAllPartialTypedLedgerWhenItStillHasAnAdmittedRuleAnchor() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(COMPACT_OUTLINE));
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(new PageInput(
                        1,
                        "UNSAFE_ALL_PARTIAL_DISPLAY_TEXT",
                        List.of(),
                        List.of("R-1"),
                        false,
                        facts("R-1"),
                        PageLedgerState.VISUAL_PARTIAL)),
                List.of(),
                "player");

        try {
            var outline = model.organize(request);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1");
            assertThat(outline.topics()).allSatisfy(topic -> assertThat(topic.coverageTags())
                    .contains(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));
            assertThat(outline.premise()).contains("第1页仅纳入了已精确绑定的视觉规则");
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            assertThat(prompt.getValue().getInstructions())
                    .allSatisfy(message -> assertThat(message.getText())
                            .doesNotContain("UNSAFE_ALL_PARTIAL_DISPLAY_TEXT"));
        } finally {
            model.close();
        }
    }

    @Test
    void keepsEveryDenseTypedFactBoundToItsOwnSlotWithoutUsingDisplaySummaries() {
        List<PageInput> pages = new java.util.ArrayList<>(IntStream.rangeClosed(1, 23)
                .mapToObj(index -> exactPage(index, "exact-" + index))
                .toList());
        List<String> identifiers = IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "dense-" + index)
                .toList();
        List<RuleGroupFact> typedFacts = IntStream.rangeClosed(1, 32)
                .mapToObj(index -> new RuleGroupFact(
                        "dense-" + index,
                        "Dense label " + index,
                        switch (index) {
                            case 1 -> "FIRST_RECORD mentions dense-2 without owning its fact.";
                            case 2 -> "SECOND_RECORD_OWN_FACT_MARKER.";
                            case 32 -> "LAST_PARTIAL_FACT_MARKER.";
                            default -> "Independent typed fact " + index + ".";
                        }))
                .toList();
        pages.add(new PageInput(
                24,
                "UNSAFE_DENSE_PARTIAL_DISPLAY_TEXT",
                List.of(),
                identifiers,
                false,
                typedFacts,
                PageLedgerState.VISUAL_PARTIAL));

        String ledger = SpringAiTeachingOutlineModel.canonicalSourceLedger(
                new OutlineRequest(List.copyOf(pages), List.of(), "player"));

        assertThat(ledger).contains(
                "SOURCE_SLOT: page-23-rule-1 | RULE_GROUP_IDENTIFIER: exact-23",
                "SOURCE_SLOT: page-24-rule-2 | RULE_GROUP_IDENTIFIER: dense-2"
                        + " | RULE_GROUP_LABEL: Dense label 2"
                        + " | RULE_GROUP_FACT: SECOND_RECORD_OWN_FACT_MARKER.",
                "SOURCE_SLOT: page-24-rule-32 | RULE_GROUP_IDENTIFIER: dense-32",
                "LAST_PARTIAL_FACT_MARKER.");
        assertThat(ledger).doesNotContain(
                "UNSAFE_EXACT_DISPLAY_TEXT_23",
                "UNSAFE_DENSE_PARTIAL_DISPLAY_TEXT");
    }

    @Test
    void keepsAnEmptyPartialPageAsAnExplicitGapWithoutCreatingASlotOrLeakingText() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(COMPACT_OUTLINE));
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(
                        exactPage(1, "R-1"),
                        new PageInput(
                                2,
                                "UNSAFE_EMPTY_PARTIAL_DISPLAY_TEXT",
                                List.of(),
                                List.of(),
                                false,
                                List.of(),
                                PageLedgerState.VISUAL_PARTIAL)),
                List.of(),
                "player");

        try {
            var outline = model.organize(request);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1");
            assertThat(outline.topics()).allSatisfy(topic -> assertThat(topic.coverageTags())
                    .contains(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));

            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            assertThat(prompt.getValue().getInstructions().stream().map(message -> message.getText()))
                    .anySatisfy(text -> assertThat(text).contains(
                            "PAGE_LEDGER_STATE: VISUAL_PARTIAL",
                            "PAGE_EVIDENCE: none (no admitted typed rule fact)"))
                    .noneSatisfy(text -> assertThat(text).contains("UNSAFE_EMPTY_PARTIAL_DISPLAY_TEXT"));
        } finally {
            model.close();
        }
    }

    @Test
    void targetedMissingSlotRepairUsesTheSameTypedPartialEvidenceBoundary() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(COMPACT_OUTLINE),
                response("""
                        {"assignments":[{"sourceSlotId":"page-2-rule-1","teachingUnitId":"choose-action"}]}
                        """));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            var outline = model.organize(exactAndPartialRequest());

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1", "R-2");
            ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel, times(2)).call(prompts.capture());
            List<String> repairInstructions = prompts.getAllValues().get(1).getInstructions().stream()
                    .map(message -> message.getText())
                    .toList();
            assertThat(repairInstructions).anySatisfy(text -> assertThat(text).contains(
                    "PAGE_LEDGER_STATE: VISUAL_PARTIAL",
                    "RULE_GROUP_FACT: Complete page-owned fact for R-2."));
            assertThat(prompts.getAllValues()).allSatisfy(prompt -> assertThat(prompt.getInstructions())
                    .allSatisfy(message -> assertThat(message.getText())
                            .doesNotContain("UNSAFE_PARTIAL_DISPLAY_TEXT")));
        } finally {
            model.close();
        }
    }

    @Test
    void keepsTargetedRepairAvailableAtTheGlobalTypedEvidenceBoundary() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(COMPACT_OUTLINE),
                response("""
                        {"assignments":[{"sourceSlotId":"page-1-rule-2","teachingUnitId":"choose-action"}]}
                        """));
        SpringAiTeachingOutlineModel model = model(configuration);
        PageInput seed = twoFactPartialPage("x");
        int seedEvidenceCharacters = SpringAiTeachingOutlineModel.canonicalPlanningEvidence(
                        seed, SpringAiTeachingOutlineModel.MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS)
                .length();
        int fillerCharacters = SpringAiTeachingOutlineModel.MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS
                - seedEvidenceCharacters
                - 7;
        PageInput nearBoundary = twoFactPartialPage("x".repeat(fillerCharacters));
        int admittedEvidenceCharacters = SpringAiTeachingOutlineModel.canonicalPlanningEvidence(
                        nearBoundary, SpringAiTeachingOutlineModel.MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS)
                .length();

        try {
            var outline = model.organize(new OutlineRequest(List.of(nearBoundary), List.of(), "player"));

            assertThat(admittedEvidenceCharacters)
                    .isEqualTo(SpringAiTeachingOutlineModel.MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS - 8);
            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1", "R-2");
            verify(chatModel, times(2)).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    @Test
    void refusesTypedVisualLedgersWithoutAnAdmittedRuleAnchorBeforeCallingAProvider() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        SpringAiTeachingOutlineModel model = model(configuration);
        var unavailableOnly = new OutlineRequest(
                List.of(unavailablePage(1, "No visual observation.")), List.of(), "player");
        var emptyPartialOnly = new OutlineRequest(
                List.of(new PageInput(
                        2,
                        "No admitted typed rule.",
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        PageLedgerState.VISUAL_PARTIAL)),
                List.of(),
                "player");

        try {
            assertThatThrownBy(() -> model.organize(unavailableOnly))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasMessageContaining("no safe canonical source ledger");
            assertThatThrownBy(() -> model.organize(emptyPartialOnly))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasMessageContaining("no safe canonical source ledger");
            verify(chatModel, times(0)).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    private RuntimeModelConfiguration configuration() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("compatible");
        when(configuration.modelNameFor(Role.TEACHING, "player")).thenReturn("teaching-test-model");
        return configuration;
    }

    private ChatModel chatModel(RuntimeModelConfiguration configuration) {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(options);
        when(chatModel.getOptions()).thenReturn(options);
        return chatModel;
    }

    private SpringAiTeachingOutlineModel model(RuntimeModelConfiguration configuration) {
        return new SpringAiTeachingOutlineModel(
                configuration,
                mock(VersionedAgentPrompts.class),
                0.1,
                "Return one exact JSON outline.",
                "Goal={learningGoal}\nPages={pages}\nImages={visualPages}\nRepair={repair}");
    }

    private OutlineRequest partialVisualRequest() {
        return new OutlineRequest(
                List.of(
                        exactPage(1, "R-1"),
                        unavailablePage(2, "UNSAFE_UNAVAILABLE_DISPLAY_TEXT")),
                List.of(),
                "player");
    }

    private OutlineRequest exactAndPartialRequest() {
        return new OutlineRequest(
                List.of(
                        exactPage(1, "R-1"),
                        new PageInput(
                                2,
                                "UNSAFE_PARTIAL_DISPLAY_TEXT",
                                List.of(),
                                List.of("R-2"),
                                false,
                                facts("R-2"),
                                PageLedgerState.VISUAL_PARTIAL)),
                List.of(),
                "player");
    }

    private PageInput exactPage(int pageNumber, String identifier) {
        return new PageInput(
                pageNumber,
                "UNSAFE_EXACT_DISPLAY_TEXT_" + pageNumber,
                List.of(),
                List.of(identifier),
                true,
                facts(identifier),
                PageLedgerState.VISUAL_EXACT_COMPLETE);
    }

    private PageInput twoFactPartialPage(String secondFact) {
        return new PageInput(
                1,
                "UNSAFE_NEAR_BOUNDARY_DISPLAY_TEXT",
                List.of(),
                List.of("R-1", "R-2"),
                false,
                List.of(
                        new RuleGroupFact("R-1", "First rule", "A small admitted fact."),
                        new RuleGroupFact("R-2", "Second rule", secondFact)),
                PageLedgerState.VISUAL_PARTIAL);
    }

    private PageInput unavailablePage(int pageNumber, String text) {
        return new PageInput(
                pageNumber,
                text,
                List.of(),
                List.of(),
                false,
                List.of(),
                PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
