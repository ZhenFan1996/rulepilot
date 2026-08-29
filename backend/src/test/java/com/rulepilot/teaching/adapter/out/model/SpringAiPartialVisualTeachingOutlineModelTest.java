package com.rulepilot.teaching.adapter.out.model;

import static com.rulepilot.teaching.VisualRuleGroupTestFacts.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCall;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCallExecutor;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.application.TeachingSourceCoverageContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
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
            assertThat(outline.premise()).isEqualTo("先理解可验证的行动关系。");

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
            assertThat(outline.premise()).isEqualTo("先理解可验证的行动关系。");

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
            assertThat(outline.premise()).isEqualTo("先理解可验证的行动关系。");
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
    void givesTheSameAgentItsCompleteRejectedObservationBeforeAChangedCandidate() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(COMPACT_OUTLINE),
                response(COMPACT_OUTLINE_WITH_PARTIAL_SLOT));
        SpringAiTeachingOutlineModel model = model(configuration);
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            var outline = model.organize(exactAndPartialRequest(), calls);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1", "R-2");
            ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel, times(2)).call(prompts.capture());
            List<String> replacementInstructions = prompts.getAllValues().get(1).getInstructions().stream()
                    .map(message -> message.getText())
                    .toList();
            assertThat(replacementInstructions).anySatisfy(text -> assertThat(text).contains(
                    "PAGE_LEDGER_STATE: VISUAL_PARTIAL",
                    "RULE_GROUP_FACT: Complete page-owned fact for R-2.",
                    "<untrusted_outline_rejection_observation>",
                    "\"candidateJson\"",
                    "\"validationError\":\"compact teaching outline omitted canonical source slots: [page-2-rule-1]",
                    "\"outputContract\"",
                    "\"allowedIdentities\":[\"page-1-rule-1\",\"page-2-rule-1\"]",
                    "Generate one new",
                    "complete JSON object"));
            assertThat(replacementInstructions).allSatisfy(text -> assertThat(text).doesNotContain(
                    "Frozen teaching units",
                    "assignments",
                    "replacements"));
            assertThat(calls.rejections).singleElement().asString().contains(
                    "Teaching outline candidate rejected: compact teaching outline omitted canonical source slots: [page-2-rule-1]");
            assertThat(prompts.getAllValues()).allSatisfy(prompt -> assertThat(prompt.getInstructions())
                    .allSatisfy(message -> assertThat(message.getText())
                            .doesNotContain("UNSAFE_PARTIAL_DISPLAY_TEXT")));
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

    @Test
    void plansALargeLedgerInBoundedShardsAndKeepsUnavailablePagesOutOfSourceOwnership() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        CountDownLatch localShardsStarted = new CountDownLatch(2);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("FIRST_LONG_FACT_MARKER")) {
                awaitConcurrentLocalShards(localShardsStarted);
                return response("""
                        {"teachingUnits":[{"role":"LEGAL_ACTION",\
                        "sourceSlotIds":["page-1-rule-1"]}]}
                        """);
            }
            if (prompt.contains("SECOND_LONG_FACT_MARKER")) {
                awaitConcurrentLocalShards(localShardsStarted);
                return response("""
                        {"teachingUnits":[{"role":"LEGAL_ACTION",\
                        "sourceSlotIds":["page-2-rule-1"]}]}
                        """);
            }
            if (prompt.contains("unit-1-1") && prompt.contains("unit-2-1")) {
                return response("""
                        {
                          "gameTitle":"长规则示例",
                          "premise":"先建立两个来源单元之间的关系。",
                          "topics":[{
                            "key":"whole-flow",
                            "objective":"理解两个已验证规则单元的先后关系。",
                            "required":true,
                            "visualEvidenceRecommended":false,
                            "teachingUnitIds":["unit-1-1","unit-2-1"]
                          }],
                          "wholeGameUnderstanding":{
                            "summary":"两个已验证单元共同构成可讲解流程。",
                            "concepts":[{
                              "conceptId":"verified-flow",
                              "label":"已验证流程",
                              "explanation":"两个来源单元按同一章节组织。",
                              "sourceSlotIds":["page-1-rule-1","page-2-rule-1"],
                              "relatedTopicKeys":["whole-flow"],
                              "prerequisiteConceptIds":[]
                            }],
                            "topicDependencies":[]
                          }
                        }
                        """);
            }
            throw new AssertionError("unexpected outline prompt");
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(
                        longExactPage(1, "LONG-1", "FIRST_LONG_FACT_MARKER"),
                        longExactPage(2, "LONG-2", "SECOND_LONG_FACT_MARKER"),
                        unavailablePage(3, "UNSAFE_UNAVAILABLE_LONG_PAGE")),
                List.of(),
                "player");
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            assertThat(SpringAiTeachingOutlineModel.requiresHierarchicalPlanning(request)).isTrue();

            var outline = model.organize(request, calls);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.slotId())
                    .containsExactly("page-1-rule-1", "page-2-rule-1");
            assertThat(outline.topics()).singleElement().satisfies(topic -> assertThat(topic.coverageTags())
                    .contains(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));
            List<String> operations = calls.calls.stream().map(ModelCall::operation).toList();
            assertThat(operations.subList(0, 2))
                    .containsExactlyInAnyOrder(
                            "organizeTeachingOutline|canonical-shard-1",
                            "organizeTeachingOutline|canonical-shard-2");
            assertThat(operations.getLast()).isEqualTo("organizeTeachingOutline|canonical-global-ordering");

            ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel, times(3)).call(prompts.capture());
            List<String> promptTexts = prompts.getAllValues().stream().map(this::promptText).toList();
            String firstShardPrompt = promptTexts.stream()
                    .filter(prompt -> prompt.contains("FIRST_LONG_FACT_MARKER"))
                    .findFirst()
                    .orElseThrow();
            String secondShardPrompt = promptTexts.stream()
                    .filter(prompt -> prompt.contains("SECOND_LONG_FACT_MARKER"))
                    .findFirst()
                    .orElseThrow();
            String globalPrompt = promptTexts.stream()
                    .filter(prompt -> prompt.contains("unit-1-1") && prompt.contains("unit-2-1"))
                    .findFirst()
                    .orElseThrow();
            assertThat(firstShardPrompt).contains("FIRST_LONG_FACT_MARKER").doesNotContain("SECOND_LONG_FACT_MARKER");
            assertThat(secondShardPrompt).contains("SECOND_LONG_FACT_MARKER").doesNotContain("FIRST_LONG_FACT_MARKER");
            assertThat(globalPrompt)
                    .contains("unit-1-1", "unit-2-1", "unavailable-page-3")
                    .doesNotContain(
                            "FIRST_LONG_FACT_MARKER",
                            "SECOND_LONG_FACT_MARKER",
                            "RULE_GROUP_FACT:",
                            "UNSAFE_UNAVAILABLE_LONG_PAGE");
        } finally {
            model.close();
        }
    }

    @Test
    void keepsEveryCanonicalSlotFromOneDensePageInOneOwnershipShard() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("FIRST_DENSE_FACT") && prompt.contains("SECOND_DENSE_FACT")) {
                return response("""
                        {"teachingUnits":[{"role":"LEGAL_ACTION",\
                        "sourceSlotIds":["page-1-rule-1","page-1-rule-2"]}]}
                        """);
            }
            if (prompt.contains("THIRD_DENSE_FACT")) {
                return response("""
                        {"teachingUnits":[{"role":"ENDING",\
                        "sourceSlotIds":["page-2-rule-1"]}]}
                        """);
            }
            if (prompt.contains("unit-1-1") && prompt.contains("unit-2-1")) {
                return response("""
                        {
                          "gameTitle":"密集页面示例",
                          "premise":"页面内关系先形成单元，再组织整份规则流程。",
                          "topics":[{
                            "key":"page-flow",
                            "objective":"理解各页规则之间的关系。",
                            "required":true,
                            "visualEvidenceRecommended":false,
                            "teachingUnitIds":["unit-1-1","unit-2-1"]
                          }],
                          "wholeGameUnderstanding":{
                            "summary":"页面内规则保持共同上下文并连接为完整流程。",
                            "concepts":[{
                              "conceptId":"page-flow",
                              "label":"页面流程",
                              "explanation":"同页槽位共同归组，再与后续页面连接。",
                              "sourceSlotIds":["page-1-rule-1","page-1-rule-2","page-2-rule-1"],
                              "relatedTopicKeys":["page-flow"],
                              "prerequisiteConceptIds":[]
                            }],
                            "topicDependencies":[]
                          }
                        }
                        """);
            }
            throw new AssertionError("unexpected dense-page outline prompt");
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(
                        new PageInput(
                                1,
                                "UNSAFE_DENSE_DISPLAY_TEXT",
                                List.of(),
                                List.of("R-1", "R-2"),
                                true,
                                List.of(
                                        new RuleGroupFact(
                                                "R-1", "First", "FIRST_DENSE_FACT " + "x".repeat(8_000)),
                                        new RuleGroupFact(
                                                "R-2", "Second", "SECOND_DENSE_FACT " + "y".repeat(8_000))),
                                PageLedgerState.VISUAL_EXACT_COMPLETE),
                        longExactPage(2, "R-3", "THIRD_DENSE_FACT")),
                List.of(),
                "player");
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            assertThat(SpringAiTeachingOutlineModel.requiresHierarchicalPlanning(request)).isTrue();

            var outline = model.organize(request, calls);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.slotId())
                    .containsExactly("page-1-rule-1", "page-1-rule-2", "page-2-rule-1");
            ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel, times(3)).call(prompts.capture());
            List<String> promptTexts = prompts.getAllValues().stream().map(this::promptText).toList();
            assertThat(promptTexts.stream()
                            .filter(prompt -> prompt.contains("FIRST_DENSE_FACT"))
                            .findFirst()
                            .orElseThrow())
                    .contains("FIRST_DENSE_FACT", "SECOND_DENSE_FACT");
            assertThat(promptTexts.stream()
                            .filter(prompt -> prompt.contains("unit-1-1"))
                            .findFirst()
                            .orElseThrow())
                    .contains("unit-1-1", "unit-2-1")
                    .doesNotContain(
                            "FIRST_DENSE_FACT", "SECOND_DENSE_FACT", "THIRD_DENSE_FACT", "RULE_GROUP_FACT:");
        } finally {
            model.close();
        }
    }

    @Test
    void sendsACompletePageShardPastTheFormerInputCapWithoutCroppingIt() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        String tailMarker = "COMPLETE_FACT_TAIL_AFTER_FORMER_CAP";
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("SOURCE_SLOT_ID: page-1-rule-1")) {
                assertThat(prompt).contains(tailMarker);
                return response("""
                        {"teachingUnits":[{"role":"LEGAL_ACTION","sourceSlotIds":["page-1-rule-1"]}]}
                        """);
            }
            if (prompt.contains("TEACHING_UNIT_ID: unit-1-1")) {
                return response(globalOrderingJson(List.of("unit-1-1"), "page-1-rule-1"));
            }
            throw new AssertionError("unexpected oversized-page prompt");
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(new PageInput(
                        1,
                        "UNSAFE_OVERSIZED_DISPLAY_TEXT",
                        List.of(),
                        List.of("R-1"),
                        true,
                        List.of(new RuleGroupFact(
                                "R-1",
                                "Oversized",
                                "x".repeat(70_000) + tailMarker)),
                        PageLedgerState.VISUAL_EXACT_COMPLETE)),
                List.of(),
                "player");
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            assertThat(SpringAiTeachingOutlineModel.requiresHierarchicalPlanning(request)).isTrue();
            var outline = model.organize(request, calls);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1");
        } finally {
            model.close();
        }
    }

    @Test
    void countsLargeCjkInputForAuditWithoutTurningTheEstimateIntoARejection() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("SOURCE_SLOT_ID: page-1-rule-1")) {
                return response("""
                        {"teachingUnits":[{"role":"LEGAL_ACTION","sourceSlotIds":["page-1-rule-1"]}]}
                        """);
            }
            return response(globalOrderingJson(List.of("unit-1-1"), "page-1-rule-1"));
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(new PageInput(
                        1,
                        "UNSAFE_OVERSIZED_CJK_DISPLAY_TEXT",
                        List.of(),
                        List.of("R-1"),
                        true,
                        List.of(new RuleGroupFact(
                                "R-1",
                                "超大规则",
                                "中".repeat(70_000))),
                        PageLedgerState.VISUAL_EXACT_COMPLETE)),
                List.of(),
                "player");
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            assertThat(SpringAiTeachingOutlineModel.requiresHierarchicalPlanning(request)).isTrue();
            var outline = model.organize(request, calls);

            assertThat(outline.sourceCoverageSlots()).hasSize(1);
            assertThat(calls.calls.getFirst().estimatedInputTokens()).isGreaterThan(64_000);
        } finally {
            model.close();
        }
    }

    @Test
    void completesALongLedgerInsteadOfRejectingItsEstimatedGlobalResponseBeforeAnyCall() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("SOURCE_SLOT_ID:")) {
                List<String> sourceSlotIds = sourceSlotIdsFromLocalPrompt(prompt);
                return response(localGroupingJson(sourceSlotIds));
            }
            List<String> unitIds = teachingUnitIdsFromGlobalPrompt(prompt);
            if (unitIds.isEmpty()) throw new AssertionError("global outline has no source-owned units");
            return response(globalOrderingJson(unitIds, "page-1-rule-1"));
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        List<PageInput> pages = IntStream.rangeClosed(1, 500)
                .mapToObj(page -> {
                    List<String> identifiers = IntStream.rangeClosed(1, 12)
                            .mapToObj(rule -> "R-" + page + "-" + rule)
                            .toList();
                    List<RuleGroupFact> pageFacts = IntStream.rangeClosed(1, 12)
                            .mapToObj(rule -> new RuleGroupFact(
                                    identifiers.get(rule - 1),
                                    "Bounded rule " + page + "." + rule,
                                    "A compact canonical fact that is safe in its own page shard. "
                                            + "x".repeat(32)))
                            .toList();
                    return new PageInput(
                            page,
                            "UNSAFE_AGGREGATE_DISPLAY_TEXT_" + page,
                            List.of(),
                            identifiers,
                            true,
                            pageFacts,
                            PageLedgerState.VISUAL_EXACT_COMPLETE);
                })
                .toList();
        var request = new OutlineRequest(pages, List.of(), "player");
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            assertThat(SpringAiTeachingOutlineModel.requiresHierarchicalPlanning(request)).isTrue();
            var outline = model.organize(request, calls);

            assertThat(outline.sourceCoverageSlots()).hasSize(6_000);
            assertThat(outline.topics()).singleElement().satisfies(topic ->
                    assertThat(topic.sourcePageNumbers()).hasSize(500));
        } finally {
            model.close();
        }
    }

    @Test
    void cancelsSiblingPageShardsWhenOneShardObservesAStoppedRun() throws InterruptedException {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(
                        longExactPage(1, "R-1", "FIRST_STOP_MARKER"),
                        longExactPage(2, "R-2", "SECOND_STOP_MARKER")),
                List.of(),
                "player");
        CountDownLatch firstShardStarted = new CountDownLatch(1);
        CountDownLatch firstShardInterrupted = new CountDownLatch(1);
        ModelCallExecutor stoppedCalls = new ModelCallExecutor() {
            @Override
            public <T> T invoke(
                    ModelCall call,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokens) {
                if (call.operation().endsWith("canonical-shard-1")) {
                    firstShardStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("the sibling shard was not cancelled");
                    } catch (InterruptedException interrupted) {
                        firstShardInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new AgentExecutionStoppedException(StopReason.CANCELLED);
                    }
                }
                if (call.operation().endsWith("canonical-shard-2")) {
                    try {
                        if (!firstShardStarted.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("the sibling shard did not start");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("the stop propagation check was interrupted", interrupted);
                    }
                    throw new AgentExecutionStoppedException(StopReason.CANCELLED);
                }
                throw new AssertionError("global ordering must not start after cancellation");
            }
        };

        try {
            assertThatThrownBy(() -> model.organize(request, stoppedCalls))
                    .isInstanceOf(AgentExecutionStoppedException.class)
                    .hasMessageContaining("CANCELLED");
            assertThat(firstShardInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            verify(chatModel, times(0)).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    @Test
    void rejectsOutlineShardParallelismOutsideTheBoundedProviderWindow() {
        assertThatThrownBy(() -> new SpringAiTeachingOutlineModel(
                        configuration(),
                        mock(VersionedAgentPrompts.class),
                        0.1,
                        "outline system",
                        "outline user",
                        "canonical system",
                        "canonical user",
                        11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism");
    }

    @Test
    void failedShardFallsBackWithoutReplacingSuccessfulShardAndPreservesEverySafeSlotExactlyOnce() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        AtomicInteger failedShardAttempts = new AtomicInteger();
        String firstRejectedCandidate = """
                {"teachingUnits":[{"role":"LEGAL_ACTION",\
                "sourceSlotIds":["unknown-source-slot"]}]}
                """;
        String changedRejectedCandidate = """
                {"teachingUnits":[
                  {"role":"LEGAL_ACTION","sourceSlotIds":["page-1-rule-1"]},
                  {"role":"SUPPORTING_RULE","sourceSlotIds":["page-1-rule-1"]}
                ]}
                """;
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("FAILED_SHARD_MARKER")) {
                int attempt = failedShardAttempts.incrementAndGet();
                if (attempt == 1 || attempt == 3) {
                    return response(firstRejectedCandidate);
                }
                if (attempt == 2) return response(changedRejectedCandidate);
                throw new AssertionError("cyclical local rejection should have settled as no progress");
            }
            if (prompt.contains("HEALTHY_SHARD_MARKER")) {
                return response("""
                        {"teachingUnits":[{"role":"LEGAL_ACTION",\
                        "sourceSlotIds":["page-2-rule-1","page-2-rule-2"]}]}
                        """);
            }
            if (prompt.contains("TEACHING_UNIT_ID:")) {
                List<String> unitIds = teachingUnitIdsFromGlobalPrompt(prompt);
                if (unitIds.size() != 3) throw new AssertionError("unexpected safe unit count");
                String unitIdsJson = unitIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
                return response("""
                        {
                          "gameTitle":"局部分片降级示例",
                          "premise":"按已验证来源继续组织讲解。",
                          "topics":[{
                            "key":"verified-rules",
                            "objective":"理解仍然可验证的两个规则来源。",
                            "required":true,
                            "visualEvidenceRecommended":false,
                            "teachingUnitIds":[%s]
                          }],
                          "wholeGameUnderstanding":{
                            "summary":"四个来源槽位仍可形成有引用的讲解。",
                            "concepts":[{
                              "conceptId":"verified-rules",
                              "label":"已验证规则",
                              "explanation":"归组失败不改变来源事实。",
                              "sourceSlotIds":["page-1-rule-1","page-1-rule-2",\
                              "page-2-rule-1","page-2-rule-2"],
                              "relatedTopicKeys":["verified-rules"],
                              "prerequisiteConceptIds":[]
                            }],
                            "topicDependencies":[]
                          }
                        }
                        """.formatted(unitIdsJson));
            }
            throw new AssertionError("unexpected local-failure outline prompt");
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(
                        new PageInput(
                                1,
                                "UNSAFE_FAILED_SHARD_DISPLAY_TEXT",
                                List.of(),
                                List.of("R-1", "R-2"),
                                true,
                                List.of(
                                        new RuleGroupFact(
                                                "R-1", "First", "FAILED_SHARD_MARKER " + "x".repeat(17_000)),
                                        new RuleGroupFact("R-2", "Second", "y".repeat(17_000))),
                                PageLedgerState.VISUAL_EXACT_COMPLETE),
                        new PageInput(
                                2,
                                "UNSAFE_HEALTHY_SHARD_DISPLAY_TEXT",
                                List.of(),
                                List.of("R-3", "R-4"),
                                true,
                                List.of(
                                        new RuleGroupFact(
                                                "R-3", "Third", "HEALTHY_SHARD_MARKER " + "x".repeat(17_000)),
                                        new RuleGroupFact("R-4", "Fourth", "y".repeat(17_000))),
                                PageLedgerState.VISUAL_EXACT_COMPLETE)),
                List.of(),
                "player");
        RecordingModelCalls calls = new RecordingModelCalls();
        try {
            var outline = model.organize(request, calls);

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.slotId())
                    .containsExactly(
                            "page-1-rule-1",
                            "page-1-rule-2",
                            "page-2-rule-1",
                            "page-2-rule-2");
            assertThat(outline.sourceCoverageSlots().stream()
                            .filter(slot -> slot.slotId().startsWith("page-1-"))
                            .map(slot -> slot.teachingUnitId())
                            .distinct())
                    .hasSize(2);
            assertThat(outline.sourceCoverageSlots().stream()
                            .filter(slot -> slot.slotId().startsWith("page-2-"))
                    .map(slot -> slot.teachingUnitId())
                    .distinct())
                    .hasSize(1);
            assertThat(failedShardAttempts).hasValue(3);
            assertThat(calls.rejections).anySatisfy(rejection -> assertThat(rejection)
                    .contains(
                            "organizeTeachingOutline|validation|local-1|no-progress",
                            "previously rejected complete candidate and observation repeated"));
            TeachingSourceCoverageContract.validateAgainstSources(request, outline);
        } finally {
            model.close();
        }
    }

    @Test
    void stopsTheGlobalOutlineWhenACompleteRejectionObservationRecursAfterAnInterveningChange() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        AtomicInteger globalAttempts = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = promptText(invocation.getArgument(0));
            if (prompt.contains("SOURCE_SLOT_ID: page-1-rule-1")) {
                return response(localGroupingJson(List.of("page-1-rule-1")));
            }
            if (prompt.contains("TEACHING_UNIT_ID: unit-1-1")) {
                return switch (globalAttempts.incrementAndGet()) {
                    case 1, 3 -> response("{}");
                    case 2 -> response("{\"gameTitle\":\"changed global candidate\"}");
                    default -> throw new AssertionError(
                            "cyclical global rejection should have settled as no progress");
                };
            }
            throw new AssertionError("unexpected cyclical global-outline prompt");
        });
        SpringAiTeachingOutlineModel model = model(configuration);
        var request = new OutlineRequest(
                List.of(new PageInput(
                        1,
                        "UNSAFE_GLOBAL_CYCLE_DISPLAY_TEXT",
                        List.of(),
                        List.of("R-1"),
                        true,
                        List.of(new RuleGroupFact(
                                "R-1",
                                "Global cycle",
                                "GLOBAL_CYCLE_MARKER " + "x".repeat(70_000))),
                        PageLedgerState.VISUAL_EXACT_COMPLETE)),
                List.of(),
                "player");
        RecordingModelCalls calls = new RecordingModelCalls();

        try {
            assertThat(SpringAiTeachingOutlineModel.requiresHierarchicalPlanning(request)).isTrue();
            assertThatThrownBy(() -> model.organize(request, calls))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasRootCauseMessage(
                            "OUTLINE_NO_PROGRESS: the global outline Agent repeated a previously rejected complete candidate, validation error, output contract, and allowed identities");
            assertThat(globalAttempts).hasValue(3);
            assertThat(calls.rejections).anySatisfy(rejection -> assertThat(rejection)
                    .contains(
                            "organizeTeachingOutline|validation|global|no-progress",
                            "previously rejected complete candidate and observation repeated"));
            verify(chatModel, times(4)).call(any(Prompt.class));
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
        when(configuration.resolvedModelFor(Role.TEACHING, "player"))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "compatible", "teaching-test-model", false));
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

    private PageInput longExactPage(int pageNumber, String identifier, String marker) {
        return new PageInput(
                pageNumber,
                "UNSAFE_LONG_DISPLAY_TEXT_" + pageNumber,
                List.of(),
                List.of(identifier),
                true,
                List.of(new RuleGroupFact(
                        identifier,
                        "Long typed fact " + pageNumber,
                        marker + " " + "x".repeat(17_000))),
                PageLedgerState.VISUAL_EXACT_COMPLETE);
    }

    private String promptText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(message -> message.getText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<String> teachingUnitIdsFromGlobalPrompt(String prompt) {
        return prompt.lines()
                .filter(line -> line.startsWith("TEACHING_UNIT_ID: "))
                .map(line -> line.substring("TEACHING_UNIT_ID: ".length(), line.indexOf(" | ROLE:")))
                .toList();
    }

    private List<String> sourceSlotIdsFromLocalPrompt(String prompt) {
        return prompt.lines()
                .filter(line -> line.startsWith("SOURCE_SLOT_ID: "))
                .map(line -> line.substring("SOURCE_SLOT_ID: ".length()))
                .toList();
    }

    private String localGroupingJson(List<String> sourceSlotIds) {
        String slots = sourceSlotIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"teachingUnits\":[{\"role\":\"LEGAL_ACTION\",\"sourceSlotIds\":[" + slots + "]}]}";
    }

    private String globalOrderingJson(List<String> teachingUnitIds, String conceptSourceSlotId) {
        String units = teachingUnitIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {
                  "gameTitle":"长规则示例",
                  "premise":"按完整来源账本组织讲解。",
                  "topics":[{
                    "key":"all-rules",
                    "objective":"理解来源账本中的全部规则单元。",
                    "required":true,
                    "visualEvidenceRecommended":false,
                    "teachingUnitIds":[%s]
                  }],
                  "wholeGameUnderstanding":{
                    "summary":"完整来源账本已经形成整局认识。",
                    "concepts":[{
                      "conceptId":"source-ledger",
                      "label":"来源账本",
                      "explanation":"全部章节都由同一不可变来源账本约束。",
                      "sourceSlotIds":["%s"],
                      "relatedTopicKeys":["all-rules"],
                      "prerequisiteConceptIds":[]
                    }],
                    "topicDependencies":[]
                  }
                }
                """.formatted(units, conceptSourceSlotId);
    }

    private void awaitConcurrentLocalShards(CountDownLatch localShardsStarted) {
        localShardsStarted.countDown();
        try {
            if (!localShardsStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("independent canonical page shards did not overlap");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("canonical page-shard concurrency check was interrupted", interrupted);
        }
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

    private static final class RecordingModelCalls implements ModelCallExecutor {
        private final List<ModelCall> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> rejections = Collections.synchronizedList(new ArrayList<>());

        @Override
        public <T> T invoke(
                ModelCall call,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokens) {
            calls.add(call);
            return invocation.get();
        }

        @Override
        public void recordRejection(String operation, String summary) {
            rejections.add(operation + ": " + summary);
        }
    }
}
