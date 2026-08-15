package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellVerificationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierReferencePage;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;

class SpringAiVisualRulebookPageCatalogModelTest {

    @Test
    void teachingStartupPromptKeepsEvidenceAtomicAndDefersVisualEnrichment() throws IOException {
        String prompt = new ClassPathResource("prompts/visual-page-teaching-catalog-v2-quantitative-scope-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains(
                "Inspect only the supplied page images",
                "Do not use prior knowledge of the named game",
                "preserve the visible subject, action, condition, quantity, timing, order",
                "scoring owner, unit, aggregation dimension",
                "per-item or per-category scope",
                "repetition count, multiplier",
                "same-scope example to cross-check",
                "Never rewrite a repeated calculation as a single local subtotal",
                "state its visible non-gameplay role",
                "ruleGroupIdentifiers",
                "ruleGroupInventoryComplete",
                "factualSummary: up to sixteen atomic",
                "must fit within 4,000 Unicode characters",
                "never rely on text beyond that limit",
                "never return an array or a shortened title",
                "must always be an explicit array with no duplicates",
                "every distinct readable gameplay rule group",
                "Do not inventory icons, propose rectangles or coordinates",
                "Icon and spatial tasks belong to a later enrichment pass");
    }

    @Test
    void progressiveTeachingPromptKeepsFactsAndSourceDependenciesInsideTheirDurableContracts() throws IOException {
        String prompt = new ClassPathResource(
                        "prompts/visual-page-progressive-teaching-start-v2-source-coverage-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains(
                "must fit within 4,000 Unicode characters",
                "never rely on text beyond that limit",
                "title as one non-empty string",
                "never return an array or a shortened title",
                "must always be an explicit array with no duplicates");
    }

    @Test
    void identifierCellPromptRequiresExactCrossPageArtworkAndPreservesRewardTiming() throws IOException {
        String prompt = new ClassPathResource("prompts/visual-identifier-cell-v1-system.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(prompt).contains(
                "Require the same distinctive shape",
                "rectangular card or sheet pictogram",
                "lower-space reward",
                "do not change it to a tile-acquisition reward");
    }

    @Test
    void referenceMatcherSeparatesLowerRewardFromAnUpperScoreMedallion() throws IOException {
        String prompt = new ClassPathResource("prompts/visual-identifier-reference-match-v1-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains(
                "When the draft distinguishes upper and lower spaces",
                "match only the compact lower-space reward",
                "never report the upper-space medallion as the matched resource");
    }

    @Test
    void acceptsOnlySuppliedIdentifierBindingsAndPreservesAtomicCellFacts() {
        var locations = SpringAiVisualRulebookPageCatalogModel.parseIdentifierLocations("""
                {"items":[
                  {"identifier":"A-01","x":10,"y":20,"width":30,"height":10},
                  {"identifier":"B#02","x":500,"y":20,"width":35,"height":10}
                ]}
                """);
        assertThat(locations.locations()).extracting(location -> location.identifier())
                .containsExactly("A-01", "B#02");

        var facts = SpringAiVisualRulebookPageCatalogModel.parseIdentifierCellFacts("""
                {"items":[
                  {"identifier":"A-01","factualSummary":"A-01：支付一个蓝色方块后移动。"},
                  {"identifier":"B#02","factualSummary":"B#02：上格得2分，下格抽一张牌。"},
                  {"identifier":"X-99","factualSummary":"不得进入结果。"}
                ]}
                """, List.of("A-01", "B#02"));
        assertThat(facts.facts()).extracting(fact -> fact.identifier())
                .containsExactly("A-01", "B#02");
        assertThat(facts.facts()).extracting(fact -> fact.factualSummary())
                .containsExactly("A-01：支付一个蓝色方块后移动。", "B#02：上格得2分，下格抽一张牌。");
    }

    @Test
    void referenceVerificationCannotPublishALabelOutsideTheDocumentEvidence() {
        var image = new PageImageInput(3, "image/png", new byte[] {1});
        var request = new IdentifierCellVerificationRequest(
                new IdentifierCellInput("B#02", image),
                new IdentifierReferencePage(image, "2 amber, 3 teal, and 1 card"),
                List.of("amber", "teal", "card"),
                "B#02: lower reward is unclear.",
                "owner");

        var accepted = SpringAiVisualRulebookPageCatalogModel.parseIdentifierCellVerification("""
                {"identifier":"B#02","matchedLabel":"card","quantity":1,
                 "factualSummary":"B#02: the lower-space reward is 1 card."}
                """, request);
        assertThat(accepted.matchedLabel()).isEqualTo("card");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SpringAiVisualRulebookPageCatalogModel.parseIdentifierCellVerification("""
                                {"identifier":"B#02","matchedLabel":"card token","quantity":1,
                                 "factualSummary":"Target identifier B#02: the lower-space reward is 1 card."}
                                """, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the evidence set");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SpringAiVisualRulebookPageCatalogModel.parseIdentifierCellVerification("""
                                {"identifier":"B#02","matchedLabel":"amber","quantity":4}
                                """, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity from reference evidence");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SpringAiVisualRulebookPageCatalogModel.parseIdentifierCellVerification("""
                                {"identifier":"B#02","matchedLabel":"coins","quantity":1,
                                 "factualSummary":"B#02: reward is coins."}
                                """, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the evidence set");
    }

    @Test
    void cropPublicationGateKeepsCompleteCentralSymbolsWithoutTrustingNeighborFragments() throws IOException {
        String prompt = new ClassPathResource("prompts/visual-icon-crop-review-v3-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt)
                .contains(
                        "single compact graphic nearest the crop center",
                        "separated from all four crop edges by visible background",
                        "tiny clipped edge of a different neighboring symbol may be ignored",
                        "isolated stylized resource cube is a valid pictogram",
                        "grid or board with multiple cells");
    }

    @Test
    void disablesSamplingForReplayableQwenVisualCatalogDecisions() {
        var options = SpringAiVisualRulebookPageCatalogModel.qwenJsonOptions("qwen3-vl-235b-a22b-instruct", 1_000)
                .build();

        assertThat(options.getModel()).isEqualTo("qwen3-vl-235b-a22b-instruct");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getMaxTokens()).isEqualTo(1_000);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void routesOnlyTheQwenTeachingStartupRequestToTheFastStructuredVisualModel() throws IOException {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder().model("qwen3.7-plus").build();
        when(configuration.usesFake(Role.VISUAL, "owner")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "owner")).thenReturn(true);
        when(configuration.providerFor(Role.VISUAL, "owner")).thenReturn("qwen");
        when(configuration.modelNameFor(Role.VISUAL, "owner")).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.VISUAL, "owner")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"pages":[{"pageNumber":1,"printedTerms":"SETUP",
                         "factualSummary":"SETUP: Each player takes one card.","keywords":["setup"],
                         "sourceDependencies":[],"ruleGroupIdentifiers":["SETUP"],
                         "ruleGroupInventoryComplete":true}]}
                        """)))));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        CatalogRequest request = new CatalogRequest(
                List.of(new PageImageInput(1, "image/png", png())), "owner", "Example Game");
        CatalogDraft teachingDraft = model.summarizeForTeaching(request);

        assertThat(teachingDraft.pages()).singleElement().satisfies(page -> {
            assertThat(page.ruleGroupIdentifiers()).containsExactly("SETUP");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.6-flash");
        assertThat(options.getMaxTokens()).isEqualTo(3_200);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getTemperature()).isZero();
        assertThat(prompt.getValue().getInstructions().stream()
                        .map(message -> message.getText().replaceAll("\\s+", " "))
                        .toList())
                .anySatisfy(text -> assertThat(text).contains(
                "A direction to use another guide, sheet, booklet, or document",
                "sourceDependencies",
                "missingCoverageTags",
                "does not supply that missing procedure"));
        assertThat(model.teachingStartupExecutionIdentity("owner")).hasValueSatisfying(identity -> {
            assertThat(identity.provider()).isEqualTo("qwen");
            assertThat(identity.model()).isEqualTo("qwen3.6-flash");
        });

        model.summarize(request);

        ArgumentCaptor<Prompt> allPrompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(allPrompts.capture());
        OpenAiChatOptions completeAuditOptions =
                (OpenAiChatOptions) allPrompts.getAllValues().getLast().getOptions();
        assertThat(completeAuditOptions.getModel()).isEqualTo("qwen3.7-plus");
    }

    @Test
    void preservesEveryExplicitNonTargetModelInsteadOfSilentlyReplacingIt() {
        assertThat(SpringAiVisualRulebookPageCatalogModel.teachingStartupModelName("qwen", "qwen3.6-plus"))
                .isEqualTo("qwen3.6-plus");
        assertThat(SpringAiVisualRulebookPageCatalogModel.teachingStartupModelName("qwen", "qwen3-vl-flash"))
                .isEqualTo("qwen3-vl-flash");
        assertThat(SpringAiVisualRulebookPageCatalogModel.teachingStartupModelName("gemini", "gemini-2.5-flash"))
                .isEqualTo("gemini-2.5-flash");
    }

    @Test
    void progressiveTeachingStartUsesQwenFlashAndKeepsExactPageRolesSeparateFromSelectedFacts() throws IOException {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder().model("qwen3.7-plus").build();
        when(configuration.usesFake(Role.VISUAL, "owner")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "owner")).thenReturn(true);
        when(configuration.providerFor(Role.VISUAL, "owner")).thenReturn("qwen");
        when(configuration.modelNameFor(Role.VISUAL, "owner")).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.VISUAL, "owner")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"pageSketches":[
                          {"pageNumber":1,"role":"NON_GAMEPLAY","visibleHeading":"Point Salad",
                           "visibleTerms":[],"coverageTags":[],"ruleGroupInventoryComplete":false,
                           "sourceDependencies":[]},
                          {"pageNumber":2,"role":"GAMEPLAY_RULES","visibleHeading":"Setup",
                           "visibleTerms":["market","veggie cards"],"coverageTags":["setup"],
                           "ruleGroupInventoryComplete":true,"sourceDependencies":[]},
                          {"pageNumber":3,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                           "visibleTerms":["take cards","refill"],
                           "coverageTags":["core_loop","end","scoring"],
                           "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                         "selectedPageFacts":{"pageNumber":3,"printedTerms":["take cards","refill"],
                           "factualSummary":["take cards：当前玩家拿取可见卡牌。","refill：拿取后补满市场。"],
                           "keywords":["take cards","refill"]}}
                        """)))));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(1, "image/png", png()),
                        new PageImageInput(2, "image/png", png()),
                        new PageImageInput(3, "image/png", png())),
                "owner",
                "Point Salad");

        var result = model.selectProgressiveTeachingStart(request).orElseThrow();

        assertThat(result.pages()).extracting(page -> page.pageNumber()).containsExactly(1, 2, 3);
        assertThat(result.pages()).extracting(page -> page.role())
                .containsExactly(TeachingPageRole.NON_GAMEPLAY, TeachingPageRole.GAMEPLAY_RULES,
                        TeachingPageRole.GAMEPLAY_RULES);
        assertThat(result.pages()).extracting(page -> page.ruleGroupInventoryComplete())
                .containsExactly(false, true, true);
        assertThat(result.pages()).allSatisfy(page -> assertThat(page.sourceDependencies()).isEmpty());
        assertThat(result.selectedPageFacts()).satisfies(facts -> {
            assertThat(facts.pageNumber()).isEqualTo(3);
            assertThat(facts.factualSummary()).contains("补满市场");
            assertThat(facts.visualAnchors()).isEmpty();
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.6-flash");
        assertThat(options.getMaxTokens()).isEqualTo(1_600);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(prompt.getValue().getInstructions().stream()
                        .map(message -> message.getText().replaceAll("\\s+", " "))
                        .toList())
                .anySatisfy(text -> assertThat(text).contains(
                        "Select one page that contains a directly readable gameplay rule",
                        "Do not use prior knowledge",
                        "ruleGroupInventoryComplete",
                        "every distinct readable gameplay rule group",
                        "sourceDependencies",
                        "missingCoverageTags",
                        "Prefer an executable setup page, then a core turn or action page",
                        "A direction to use another guide, sheet, booklet, or document",
                        "Do not inventory icons"));
    }

    @Test
    void progressiveTeachingStartRejectsMissingOrDuplicateBindingsWithoutRebindingAcrossImages() {
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(2, "image/png", new byte[] {1}),
                        new PageImageInput(5, "image/png", new byte[] {2})),
                "owner");
        var incomplete = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":2,"role":"GAMEPLAY_RULES","visibleHeading":"A",
                   "visibleTerms":["alpha"],"coverageTags":["setup","core_loop","end","scoring"],
                   "sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":2,"printedTerms":"alpha",
                  "factualSummary":"当前玩家必须执行一个可见动作。","keywords":["alpha","rule"]}}
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SpringAiVisualRulebookPageCatalogModel.normalizeProgressiveTeachingStartBindings(
                                request, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every supplied page exactly");
    }

    @Test
    void progressiveTeachingStartPreservesEightDistinctRuleGroupsAndTheInventoryCompletenessDecision() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Available actions",
                   "visibleTerms":["move","build","trade","copy","recruit","produce","score","pass"],
                   "coverageTags":["core_loop","scoring"],"ruleGroupInventoryComplete":true,
                   "sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["move","build"],
                  "factualSummary":[
                    "move：当前玩家执行可见的移动规则。",
                    "build：当前玩家执行可见的建造规则。",
                    "trade：当前玩家执行可见的交易规则。",
                    "copy：当前玩家执行可见的复制规则。",
                    "recruit：当前玩家执行可见的招募规则。",
                    "produce：当前玩家执行可见的生产规则。",
                    "score：当前玩家执行可见的计分规则。",
                    "pass：当前玩家执行可见的跳过规则。"],
                  "keywords":["move","build"]}}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.visibleTerms()).containsExactly(
                    "move", "build", "trade", "copy", "recruit", "produce", "score", "pass");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void progressiveTeachingStartCannotClaimSelectedCompletenessWithoutEveryBoundFact() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Available actions",
                   "visibleTerms":["MOVE","BUILD"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["MOVE","BUILD"],
                  "factualSummary":["MOVE: Move one pawn."],"keywords":["MOVE","BUILD"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BUILD")
                .hasMessageContaining("same-page fact");
    }

    @Test
    void progressiveTeachingStartRequiresAnExactSelectedFactIdentifierBoundary() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Available action",
                   "visibleTerms":["MOVE"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["MOVE"],
                  "factualSummary":["MOVEMENT: Move one pawn."],"keywords":["MOVE"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOVE")
                .hasMessageContaining("same-page fact");
    }

    @Test
    void progressiveTeachingStartCannotValidateARequiredFactThatWillBeTruncatedBeforeStorage() {
        String oversizedFact = "CONTEXT: " + "x".repeat(4_050);
        String response = """
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Available action",
                   "visibleTerms":["LATE"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["LATE"],
                  "factualSummary":["%s","LATE: Apply the visible late rule."],"keywords":["LATE"]}}
                """.formatted(oversizedFact);

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factualSummary")
                .hasMessageContaining("4,000");
    }

    @Test
    void progressiveTeachingStartRejectsAnOverCapacityInventoryInsteadOfTruncatingIt() {
        String identifiers = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(index -> "\"GROUP_" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String facts = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(index -> "\"GROUP_" + index + ": visible fact " + index + ".\"")
                .collect(java.util.stream.Collectors.joining(","));
        String response = """
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Available actions",
                   "visibleTerms":[%s],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["GROUPS"],
                  "factualSummary":[%s],"keywords":["GROUPS"]}}
                """.formatted(identifiers, facts);

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 8");
    }

    @Test
    void progressiveTeachingStartCannotCompleteAnEmptyGameplayInventory() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":[],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["TURN"],
                  "factualSummary":["TURN: A visible turn relation."],"keywords":["TURN"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete gameplay inventory")
                .hasMessageContaining("empty");
    }

    @Test
    void progressiveTeachingStartRequiresAnExplicitDependencyInventoryForEveryPage() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":1,"role":"NON_GAMEPLAY","visibleHeading":"Cover",
                   "visibleTerms":[],"coverageTags":[],"ruleGroupInventoryComplete":false,
                   "sourceDependencies":[]},
                  {"pageNumber":2,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":["take action"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true}],
                 "selectedPageFacts":{"pageNumber":2,"printedTerms":["take action"],
                  "factualSummary":["当前玩家执行一个行动。"],"keywords":["take action","turn"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDependencies");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":1,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":["take action"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":null}],
                 "selectedPageFacts":{"pageNumber":1,"printedTerms":["take action"],
                  "factualSummary":["当前玩家执行一个行动。"],"keywords":["take action","turn"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDependencies");
    }

    @Test
    void progressiveTeachingStartKeepsExternalSourceDependenciesOutOfExecutableRuleGroups() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":1,"role":"GAMEPLAY_RULES","visibleHeading":"Playing the game",
                   "visibleTerms":["play a card"],"coverageTags":["core_loop"],
                   "ruleGroupInventoryComplete":true,
                   "sourceDependencies":[{"title":"Quick Start Guide","missingCoverageTags":["setup"]}]},
                  {"pageNumber":2,"role":"GAMEPLAY_RULES","visibleHeading":"End",
                   "visibleTerms":["game end","final score"],"coverageTags":["end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":1,"printedTerms":["play a card"],
                  "factualSummary":["play a card：当前玩家打出一张卡并执行行动。"],
                  "keywords":["play a card","action"]}}
                """);

        assertThat(draft.pages().getFirst()).satisfies(page -> {
            assertThat(page.visibleTerms()).containsExactly("play a card");
            assertThat(page.sourceDependencies()).singleElement().satisfies(dependency -> {
                assertThat(dependency.title()).isEqualTo("Quick Start Guide");
                assertThat(dependency.missingCoverageTags()).containsExactly("setup");
            });
        });
        assertThat(draft.pages().get(1).sourceDependencies()).isEmpty();
        assertThat(draft.selectedPageFacts().printedTerms()).contains("Quick Start Guide");
        assertThat(draft.selectedPageFacts().factualSummary())
                .contains("Quick Start Guide", "当前页本身不提供开局步骤")
                .doesNotContain("如何完成开局");
    }

    @Test
    void progressiveTeachingStartRejectsAnUnrecognizedMissingResponsibility() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":1,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":["take action"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,
                   "sourceDependencies":[{"title":"Extra Notes","missingCoverageTags":["table_feel"]}]}],
                 "selectedPageFacts":{"pageNumber":1,"printedTerms":["take action"],
                  "factualSummary":["当前玩家执行一个行动。"],"keywords":["take action","turn"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source dependency");
    }

    @Test
    void parsesQwenJsonContentWithoutDependingOnNativeStructuredOutput() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                ```json
                {"pages":[{"pageNumber":14,"printedTerms":"KODORA",
                "factualSummary":"红色图标与图例中的胜利点相同。","keywords":["KODORA","victory point"]}]}
                ```
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(14);
            assertThat(page.factualSummary()).contains("胜利点");
        });
    }

    @Test
    void completeTeachingCatalogKeepsExternalSourceDependenciesStructured() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY A CARD"],
                "factualSummary":["当前玩家打出一张牌并执行行动。"],
                "keywords":["PLAY A CARD"],
                "sourceDependencies":[
                  {"title":"First Session Booklet","missingCoverageTags":["setup"]},
                  {"title":"Reference Folio","missingCoverageTags":[]}
                ]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page ->
                assertThat(page.sourceDependencies()).containsExactly(
                        new SourceDependency("First Session Booklet", List.of("setup")),
                        new SourceDependency("Reference Folio", List.of())));
    }

    @Test
    void sourceDependencyRejectsAnOverlongTitleInsteadOfTruncatingIt() {
        String title = "Guide " + "x".repeat(160);

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                        teachingCatalogWithDependency(
                                "{\"title\":\"" + title + "\",\"missingCoverageTags\":[\"setup\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source dependency")
                .hasMessageContaining("title");
    }

    @Test
    void sourceDependencyRejectsAnArrayTitleInsteadOfJoiningIt() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                        teachingCatalogWithDependency(
                                "{\"title\":[\"Quick\",\"Start\"],\"missingCoverageTags\":[\"setup\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source dependency")
                .hasMessageContaining("title");
    }

    @Test
    void sourceDependencyRejectsAnExtraInvalidResponsibilityInsteadOfTruncatingIt() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                        teachingCatalogWithDependency(
                                "{\"title\":\"Session Guide\",\"missingCoverageTags\":["
                                        + "\"setup\",\"core_loop\",\"end\",\"scoring\",\"table_feel\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source dependency")
                .hasMessageContaining("missingCoverageTags");
    }

    @Test
    void sourceDependencyRequiresAnExplicitResponsibilityArray() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                        teachingCatalogWithDependency("{\"title\":\"Session Guide\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source dependency")
                .hasMessageContaining("missingCoverageTags");
    }

    @Test
    void sourceDependencyAcceptsFourExplicitResponsibilitiesWithoutChangingTheTitle() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                teachingCatalogWithDependency(
                        "{\"title\":\"Session Guide\",\"missingCoverageTags\":["
                                + "\"setup\",\"core_loop\",\"end\",\"scoring\"]}"));

        assertThat(draft.pages()).singleElement().satisfies(page ->
                assertThat(page.sourceDependencies()).containsExactly(new SourceDependency(
                        "Session Guide", List.of("setup", "core_loop", "end", "scoring"))));
    }

    @Test
    void completeTeachingCatalogRequiresAnExplicitDependencyInventory() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["PLAY：当前玩家执行行动。"],"keywords":["PLAY"],
                "ruleGroupIdentifiers":["PLAY"],"ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDependencies");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["PLAY：当前玩家执行行动。"],"keywords":["PLAY"],
                "sourceDependencies":null,"ruleGroupIdentifiers":["PLAY"],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDependencies");

        assertThat(SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["PLAY：当前玩家执行行动。"],"keywords":["PLAY"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["PLAY"],
                "ruleGroupInventoryComplete":true}]}
                """).pages())
                .singleElement()
                .satisfies(page -> {
                    assertThat(page.sourceDependencies()).isEmpty();
                    assertThat(page.ruleGroupIdentifiers()).containsExactly("PLAY");
                    assertThat(page.ruleGroupInventoryComplete()).isTrue();
                });
    }

    @Test
    void completeTeachingCatalogRequiresAnExplicitRuleGroupInventoryForEveryPage() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["当前玩家执行行动。"],"keywords":["PLAY"],
                "sourceDependencies":[]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleGroupIdentifiers");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["当前玩家执行行动。"],"keywords":["PLAY"],
                "sourceDependencies":[],"ruleGroupIdentifiers":null,
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleGroupIdentifiers");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["当前玩家执行行动。"],"keywords":["PLAY"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["PLAY"]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleGroupInventoryComplete");
    }

    @Test
    void completeTeachingCatalogCanConfirmAFullyCheckedNonGameplayPageWithAnEmptyRuleGroupInventory() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":1,"printedTerms":["Publisher mark"],
                "factualSummary":["该页是出版信息页，没有可见的玩法规则。"],"keywords":["publisher","credits"],
                "sourceDependencies":[],"ruleGroupIdentifiers":[],
                "ruleGroupInventoryComplete":true}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.ruleGroupIdentifiers()).isEmpty();
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void completeTeachingCatalogCannotClaimCompletenessWhenAListedRuleGroupHasNoBoundFact() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE","BUILD"],
                "factualSummary":["MOVE：当前玩家移动一个棋子。"],"keywords":["MOVE","BUILD"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["MOVE","BUILD"],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BUILD")
                .hasMessageContaining("same-page fact");
    }

    @Test
    void completeTeachingCatalogRequiresAnExactIdentifierBoundaryBeforeTheFact() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],
                "factualSummary":["MOVEMENT: Move one pawn."],"keywords":["MOVE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["MOVE"],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOVE")
                .hasMessageContaining("same-page fact");
    }

    @Test
    void completeTeachingCatalogCannotValidateARequiredFactThatWillBeTruncatedBeforeStorage() {
        String oversizedFact = "CONTEXT: " + "x".repeat(4_050);
        String response = """
                {"pages":[{"pageNumber":4,"printedTerms":["LATE"],
                "factualSummary":["%s","LATE: Apply the visible late rule."],"keywords":["LATE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["LATE"],
                "ruleGroupInventoryComplete":true}]}
                """.formatted(oversizedFact);

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factualSummary")
                .hasMessageContaining("4,000");
    }

    @Test
    void completeTeachingCatalogPreservesACompleteBoundLedgerNearTheStorageLimit() {
        String nearLimitContext = "CONTEXT: " + "x".repeat(3_900);
        String response = """
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],
                "factualSummary":["MOVE: Move one pawn.","%s"],"keywords":["MOVE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["MOVE"],
                "ruleGroupInventoryComplete":true}]}
                """.formatted(nearLimitContext);

        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(response);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.factualSummary()).contains("MOVE: Move one pawn.", nearLimitContext);
            assertThat(page.factualSummary().length()).isBetween(3_900, 4_000);
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void completeTeachingCatalogRejectsAnIdentifierWithNoRuleStatement() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],
                "factualSummary":["MOVE:"],"keywords":["MOVE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["MOVE"],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOVE")
                .hasMessageContaining("same-page fact");
    }

    @Test
    void completeTeachingCatalogRejectsAnOverCapacityInventoryInsteadOfTruncatingIt() {
        String identifiers = java.util.stream.IntStream.rangeClosed(1, 17)
                .mapToObj(index -> "\"GROUP_" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String facts = java.util.stream.IntStream.rangeClosed(1, 17)
                .mapToObj(index -> "\"GROUP_" + index + ": visible fact " + index + ".\"")
                .collect(java.util.stream.Collectors.joining(","));
        String response = """
                {"pages":[{"pageNumber":4,"printedTerms":["GROUPS"],
                "factualSummary":[%s],"keywords":["GROUPS"],
                "sourceDependencies":[],"ruleGroupIdentifiers":[%s],
                "ruleGroupInventoryComplete":true}]}
                """.formatted(facts, identifiers);

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 16");
    }

    @Test
    void completeTeachingCatalogRejectsNonTextAndNormalizedDuplicateIdentifiers() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],
                "factualSummary":["MOVE: Move one pawn."],"keywords":["MOVE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":[3],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be text");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],
                "factualSummary":["MOVE: Move one pawn."],"keywords":["MOVE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["MOVE"," move "],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicated");
    }

    private SpringAiVisualRulebookPageCatalogModel model(RuntimeModelConfiguration configuration) throws IOException {
        return new SpringAiVisualRulebookPageCatalogModel(
                configuration,
                new FakeVisualRulebookPageCatalogModel(),
                new ClassPathResource("prompts/visual-page-catalog-v2-icon-inventory-system.txt"),
                new ClassPathResource("prompts/visual-page-teaching-catalog-v2-quantitative-scope-system.txt"),
                new ClassPathResource("prompts/visual-page-progressive-teaching-start-v2-source-coverage-system.txt"),
                new ClassPathResource("prompts/visual-icon-localization-v2-system.txt"),
                new ClassPathResource("prompts/visual-icon-crop-review-v4-system.txt"),
                new ClassPathResource("prompts/visual-identifier-cell-v1-system.txt"),
                new ClassPathResource("prompts/visual-identifier-reference-match-v1-system.txt"),
                4_800);
    }

    private static String teachingCatalogWithDependency(String dependency) {
        return """
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["PLAY: The player takes one visible action."],"keywords":["PLAY"],
                "sourceDependencies":[%s],"ruleGroupIdentifiers":["PLAY"],
                "ruleGroupInventoryComplete":true}]}
                """.formatted(dependency);
    }

    private byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    @Test
    void acceptsQwenTermAndSummaryArraysWithoutDiscardingTheVisualFacts() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":7,
                "printedTerms":["power tokens","victory point token"],
                "factualSummary":["黄色图标表示能量标记。","红色图标表示胜利点。"],
                "keywords":["power tokens","victory point token"],
                "visualAnchors":[{"kind":"icon legend","label":"power tokens",
                "visibleDescription":"黄色圆形图标与 power tokens 标签相邻。",
                "x":120,"y":280,"width":240,"height":140}]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("power tokens; victory point token");
            assertThat(page.factualSummary()).contains("能量标记", "胜利点");
            assertThat(page.visualAnchors()).singleElement().satisfies(anchor -> {
                assertThat(anchor.label()).isEqualTo("power tokens");
                assertThat(anchor.x()).isEqualTo(120);
            });
        });
    }

    @Test
    void parses_a_complete_icon_inventory_without_treating_unexplained_icons_as_rules() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["Resource icons"],"factualSummary":[],
                "keywords":["resource"],
                "iconOccurrences":[
                  {"groupKey":"wood","name":"Wood","visualDescription":"棕色木块轮廓。",
                   "meaningStatus":"EXPLICIT","explanation":"表示一份木材。","evidenceText":"Wood resource",
                   "x":100,"y":240,"width":42,"height":42},
                  {"groupKey":"blue circle wave","name":"蓝色波纹圆标","visualDescription":"蓝色圆形内有波纹。",
                   "meaningStatus":"UNEXPLAINED","explanation":"","evidenceText":"",
                   "x":300,"y":240,"width":42,"height":42}
                ],"iconInventoryComplete":true}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.iconInventoryComplete()).isTrue();
            assertThat(page.iconOccurrences()).hasSize(2);
            assertThat(page.iconOccurrences().getFirst().explanation()).isEqualTo("表示一份木材。");
            assertThat(page.iconOccurrences().getLast().explanation()).isEmpty();
        });
    }

    @Test
    void drops_only_a_malformed_icon_and_keeps_the_page_inventory_incomplete() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":8,"printedTerms":"icons","factualSummary":"图标页。",
                "keywords":["icons"],"iconOccurrences":[
                  {"groupKey":"bad","name":"Bad","visualDescription":"越界图标。",
                   "meaningStatus":"UNEXPLAINED","explanation":"","evidenceText":"",
                   "x":990,"y":990,"width":80,"height":80}
                ],"iconInventoryComplete":false}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.iconOccurrences()).isEmpty();
            assertThat(page.iconInventoryComplete()).isFalse();
        });
    }

    @Test
    void keeps_the_page_catalog_when_an_optional_anchor_has_invalid_geometry() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":7,"printedTerms":"power tokens",
                "factualSummary":"黄色图标与标签相邻。","keywords":["power tokens"],
                "visualAnchors":[{"kind":"legend","label":"power tokens","visibleDescription":"黄色图标。",
                "x":900,"y":900,"width":300,"height":300}]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("power tokens");
            assertThat(page.visualAnchors()).isEmpty();
        });
    }

    @Test
    void bounds_overlong_optional_text_instead_of_discarding_the_icon_page() {
        String longSummary = "visible rule ".repeat(300);
        String json = "{\"pages\":[{\"pageNumber\":6,\"printedTerms\":\"ICONS\","
                + "\"factualSummary\":" + jsonString(longSummary) + ","
                + "\"keywords\":[\""
                + "x".repeat(180) + "\"],\"iconOccurrences\":[],\"iconInventoryComplete\":true}]}";

        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog(json);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.factualSummary()).hasSizeLessThanOrEqualTo(4_000);
            assertThat(page.factualSummary()).hasSizeGreaterThan(1_600);
            assertThat(page.keywords().getFirst()).hasSize(120);
        });
    }

    @Test
    void parsesDedicatedIconLocationsFromQwenArrayOrObjectShape() {
        var array = SpringAiVisualRulebookPageCatalogModel.parseIconLocalization("""
                [
                  {"candidateIndex":0,"present":true,"x":120,"y":300,"width":24,"height":20,"observedLabel":"WOOD"},
                  {"candidateIndex":1,"present":false}
                ]
                """, 2);
        var object = SpringAiVisualRulebookPageCatalogModel.parseIconLocalization("""
                {"items":[
                  {"candidateIndex":0,"present":true,"x":120,"y":300,"width":24,"height":20,"observedLabel":"WOOD"},
                  {"candidateIndex":1,"present":false}
                ]}
                """, 2);

        assertThat(array).isEqualTo(object);
        assertThat(array.locations()).hasSize(2);
        assertThat(array.locations().getFirst().present()).isTrue();
        assertThat(array.locations().getFirst().observedLabel()).isEqualTo("WOOD");
        assertThat(array.locations().getLast().present()).isFalse();
    }

    @Test
    void rejectsOnlyTheMalformedRectangleInsteadOfDiscardingVerifiedCandidates() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconLocalization("""
                {"items":[
                  {"candidateIndex":0,"present":true,"x":120,"y":300,"width":24,"height":20},
                  {"candidateIndex":1,"present":true,"x":995,"y":995,"width":30,"height":30}
                ]}
                """, 2);

        assertThat(result.locations().getFirst().present()).isTrue();
        assertThat(result.locations().getLast()).isEqualTo(IconLocation.absent(1));
    }

    @Test
    void parsesCloseUpCropReviewForTheExactCandidateIndexes() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconCropReview("""
                {"items":[
                  {"candidateIndex":3,"matchesAppearance":true,"fullyContained":true,"standalonePictogram":true,
                   "x":120,"y":160,"width":300,"height":280},
                  {"candidateIndex":7,"matchesAppearance":false,"fullyContained":false,"standalonePictogram":false,
                   "rejectionCode":"NO_MATCHING_PICTOGRAM"}
                ]}
                """, List.of(3, 7));

        assertThat(result.decisions()).hasSize(2);
        assertThat(result.decisions().getFirst().matchesAppearance()).isTrue();
        assertThat(result.decisions().getFirst().x()).isEqualTo(120);
        assertThat(result.decisions().getFirst().height()).isEqualTo(280);
        assertThat(result.decisions().getLast().matchesAppearance()).isFalse();
    }

    @Test
    void rejectsClippedAndMultiSymbolCropsFromThePublicationGate() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconCropReview("""
                {"items":[
                  {"candidateIndex":2,"matchesAppearance":false,"fullyContained":false,"standalonePictogram":false,
                   "rejectionCode":"CLIPPED_OR_PARTIAL"},
                  {"candidateIndex":5,"matchesAppearance":true,"fullyContained":true,"standalonePictogram":false,
                   "rejectionCode":"MULTIPLE_SYMBOLS"}
                ]}
                """, List.of(2, 5));

        assertThat(result.decisions()).containsExactly(
                IconCropDecision.rejected(2),
                IconCropDecision.rejected(5));
    }

    @Test
    void rejectsOnlyTheCandidateWhosePublicationVerdictIsMissing() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconCropReview("""
                {"items":[
                  {"candidateIndex":3,"matchesAppearance":true,"fullyContained":true,"standalonePictogram":true,
                   "x":100,"y":100,"width":200,"height":200},
                  {"candidateIndex":7,"matchesAppearance":true,"fullyContained":true,"standalonePictogram":true,
                   "rejectionCode":"ACCEPTED"}
                ]}
                """, List.of(3, 7));

        assertThat(result.decisions().getFirst().matchesAppearance()).isTrue();
        assertThat(result.decisions().getLast()).isEqualTo(IconCropDecision.rejected(7));
    }

    @Test
    void localizesFromAppearanceWithoutSemanticNamesThatCanMatchNearbyProse() {
        String candidates = SpringAiVisualRulebookPageCatalogModel.iconLocalizationCandidates(List.of(
                        new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                        "collect-no-buttons",
                        "收集零个纽扣",
                        "红色叉号叠加在白色纽扣图标上。",
                        "",
                        "",
                        com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.UNEXPLAINED,
                        100,
                        100,
                        20,
                        20)));

        assertThat(candidates).contains("visible appearance=红色叉号叠加在白色纽扣图标上。");
        assertThat(candidates).doesNotContain("collect-no-buttons", "收集零个纽扣");
    }

    @Test
    void preservesTheStructuredAppearanceInsteadOfRewritingItsSemanticVocabulary() {
        var icon = new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                "vegetable",
                "蔬菜",
                "Purple rectangular card with a white circle containing a purple vegetable illustration.",
                "",
                "",
                com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.UNEXPLAINED,
                100,
                100,
                30,
                30);

        String hint = SpringAiVisualRulebookPageCatalogModel.cropReviewAppearance(icon);
        assertThat(hint).isEqualTo(icon.visualDescription());
    }

    @Test
    void redactsFirstPassLabelsBeforeIndependentImageLabelVerification() {
        String candidates = SpringAiVisualRulebookPageCatalogModel.iconLocalizationCandidates(List.of(
                new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                        "CARROT",
                        "胡萝卜",
                        "Orange card with CARROT printed above a carrot silhouette.",
                        "代表胡萝卜类型。",
                        "CARROT",
                        com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.EXPLICIT,
                        100,
                        100,
                        20,
                        20)));

        assertThat(candidates).contains("redacted-label");
        assertThat(candidates).doesNotContain("CARROT", "胡萝卜");
    }

    @Test
    void repairsOneConcatenatedPageNumberWithoutChangingTheKnownPageBinding() {
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(7, "image/jpeg", new byte[] {1}),
                        new PageImageInput(14, "image/jpeg", new byte[] {2})),
                "owner");
        CatalogDraft draft = new CatalogDraft(List.of(
                new PageSummary(714, "power tokens", "红色图标是胜利点。", List.of("victory point")),
                new PageSummary(14, "KODORA", "下注两个胜利点。", List.of("KODORA"))));

        CatalogDraft normalized = SpringAiVisualRulebookPageCatalogModel.normalizePageBindings(request, draft);

        assertThat(normalized.pages()).extracting(PageSummary::pageNumber).containsExactly(7, 14);
        assertThat(normalized.pages().getLast().printedTerms()).isEqualTo("KODORA");
    }

    @Test
    void keepsOnlyExactUniqueBindingsFromAPartialMultiPageTeachingResponse() {
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(2, "image/jpeg", new byte[] {1}),
                        new PageImageInput(5, "image/jpeg", new byte[] {2}),
                        new PageImageInput(9, "image/jpeg", new byte[] {3})),
                "owner");
        CatalogDraft draft = new CatalogDraft(List.of(
                new PageSummary(
                        2,
                        "SETUP",
                        "SETUP: Visible setup rule.",
                        List.of("setup"),
                        List.of(new com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor(
                                "diagram", "setup", "Setup diagram.", 10, 10, 100, 100)),
                        List.of(new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                                "token",
                                "标记",
                                "A compact circle.",
                                "",
                                "",
                                com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.UNEXPLAINED,
                                10,
                                10,
                                20,
                                20)),
                        true,
                        List.of(),
                        List.of("SETUP"),
                        true),
                new PageSummary(
                        9,
                        "SCORING",
                        "SCORING: Visible scoring rule.",
                        List.of("scoring"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        List.of("SCORING"),
                        true)));

        CatalogDraft normalized =
                SpringAiVisualRulebookPageCatalogModel.normalizeTeachingPageBindings(request, draft);

        assertThat(normalized.pages()).extracting(PageSummary::pageNumber).containsExactly(2, 9);
        assertThat(normalized.pages()).allSatisfy(page -> {
            assertThat(page.visualAnchors()).isEmpty();
            assertThat(page.iconOccurrences()).isEmpty();
            assertThat(page.iconInventoryComplete()).isFalse();
            assertThat(page.ruleGroupIdentifiers()).isNotEmpty();
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void neverGuessesUnknownOrDuplicateBindingsAcrossTeachingPageImages() {
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(2, "image/jpeg", new byte[] {1}),
                        new PageImageInput(5, "image/jpeg", new byte[] {2}),
                        new PageImageInput(9, "image/jpeg", new byte[] {3})),
                "owner");
        CatalogDraft draft = new CatalogDraft(List.of(
                new PageSummary(2, "FIRST", "First candidate.", List.of("first")),
                new PageSummary(2, "DUPLICATE", "Duplicate candidate.", List.of("duplicate")),
                new PageSummary(259, "UNKNOWN", "Unknown binding.", List.of("unknown"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SpringAiVisualRulebookPageCatalogModel.normalizeTeachingPageBindings(request, draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no safely bound supplied page");
    }

    @Test
    void repairsTheOnlyBindingDuringASinglePageTeachingRetry() {
        CatalogRequest request = new CatalogRequest(
                List.of(new PageImageInput(11, "image/jpeg", new byte[] {1})), "owner");
        CatalogDraft draft = new CatalogDraft(List.of(
                new PageSummary(
                        111,
                        "TURN",
                        "TURN: Visible turn rule.",
                        List.of("turn"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        List.of("TURN"),
                        true)));

        CatalogDraft normalized =
                SpringAiVisualRulebookPageCatalogModel.normalizeTeachingPageBindings(request, draft);

        assertThat(normalized.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(11);
            assertThat(page.ruleGroupIdentifiers()).containsExactly("TURN");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void boundsMultiImageCatalogRequestsAtOneShortRulebook() {
        List<PageImageInput> eightPages = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(page -> new PageImageInput(page, "image/jpeg", new byte[] {(byte) page}))
                .toList();

        assertThat(new CatalogRequest(eightPages, "owner").pages()).hasSize(8);
        assertThat(new CatalogDraft(eightPages.stream()
                        .map(page -> new PageSummary(
                                page.pageNumber(), "PAGE", "Visible fact.", List.of("page")))
                        .toList()).pages())
                .hasSize(8);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CatalogRequest(
                        java.util.stream.IntStream.rangeClosed(1, 9)
                                .mapToObj(page -> new PageImageInput(page, "image/jpeg", new byte[] {(byte) page}))
                                .toList(),
                        "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog request is invalid");
    }

    private static String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
