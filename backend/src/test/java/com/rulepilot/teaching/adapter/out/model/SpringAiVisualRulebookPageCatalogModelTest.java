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
import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.adapter.out.QuotaAwareChatModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRepairCode;
import com.rulepilot.teaching.VisualQuantityObservation.QuantityResolution;
import com.rulepilot.teaching.VisualQuantityObservation.QuantifierScope;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
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
    void teachingStartupPromptKeepsLiteralQuantityEvidenceAtomicAndDefersVisualEnrichment() throws IOException {
        String prompt = new ClassPathResource("prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains(
                "Inspect only the supplied page images",
                "use prior knowledge of the named game",
                "Keep the subject, operation, condition, quantity, timing, order",
                "For scoring, keep owner, counted object, unit, aggregation dimension",
                "per-item/per-category scope",
                "repetition count, multiplier",
                "same-scope worked example",
                "Never flatten a repeated calculation into one local subtotal",
                "quantitySpans",
                "do not return a separate quantityObservations array",
                "quantity contract is copy-only",
                "Do not classify a value as total, per-player, per-variant, range, threshold, or formula",
                "Player-count, age, duration, publisher, and award badges are page metadata",
                "A cross-reference to another numbered page in this same rulebook",
                "is never a source dependency",
                "return enough quantitySpans in that same ruleGroups object",
                "Written counts are quantities too",
                "scan each ruleGroups object",
                "reread ambiguous digits from the image",
                "stated comparison or threshold relationship are mutually coherent",
                "keep the visible role in printedTerms",
                "ruleGroups",
                "identifier, fact, and quantitySpans",
                "Identifiers must be unique within one page",
                "shortest exact visible opening phrase for each later group",
                "The fact does not need to repeat or prefix itself with identifier",
                "Use quantitySpans only for a visible number",
                "ruleGroupInventoryComplete",
                "every distinct readable gameplay group",
                "Inventory each distinct labelled block, list row, table row, and worked example",
                "never replace them with a heading-only overview",
                "zero to sixteen visible original-language retrieval terms",
                "Return an empty array",
                "never determine whether the rule-group inventory is complete",
                "Do not inventory icons, propose rectangles or coordinates",
                "Those belong to later enrichment")
                .doesNotContain(
                        "2-8 visible original-language retrieval terms",
                        "For every ruleGroupIdentifiers item",
                        "quantifierScope",
                        "resolution",
                        "ruleGroupIndex",
                        "originalSpan",
                        "derivedTotal",
                        "PER_VARIANT_EXACT",
                        "TOTAL_EXACT");
    }

    @Test
    void literalQuantitySpansPreserveRangesAndTableThresholdsWithoutModelArithmetic() {
        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":12,"printedTerms":["PLAYERS","RESULT"],
                "keywords":["PLAYERS","RESULT"],"externalDocumentDependencies":[],
                "ruleGroups":[
                  {"identifier":"PLAYERS","fact":"该模式支持1至4名玩家，时长80分钟。",
                   "quantitySpans":["1-4 PLAYERS · 80 MIN"]},
                  {"identifier":"RESULT","fact":"三个或更少为失败，四个为普通成功，七个为惊艳。",
                   "quantitySpans":["3 or fewer Failure","4 Fair success","7 Stunning success"]}],
                "ruleGroupInventoryComplete":true}]}
                """);

        assertThat(accepted.pages().getFirst().quantityObservations())
                .hasSize(4)
                .allSatisfy(observation -> {
                    assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.LITERAL_SOURCE_SPAN);
                    assertThat(observation.resolution()).isEqualTo(QuantityResolution.TRANSCRIBED_SOURCE_SPAN);
                    assertThat(observation.variantCount()).isNull();
                    assertThat(observation.perVariantQuantity()).isNull();
                    assertThat(observation.derivedTotal()).isNull();
                });

        var partialEvidence = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":12,"printedTerms":["PLAYERS"],
                "keywords":["PLAYERS","PLAYER COUNT"],"externalDocumentDependencies":[],
                "ruleGroups":[{"identifier":"PLAYERS","fact":"该模式支持1至4名玩家，时长80分钟。",
                 "quantitySpans":["1-4 PLAYERS"]}],
                "ruleGroupInventoryComplete":true}]}
                """);
        assertThat(partialEvidence.pages().getFirst().quantityObservations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.originalSpan()).isEqualTo("1-4 PLAYERS"));
    }

    @Test
    void literalQuantitySpansRejectDuplicateEvidenceInsteadOfSilentlyRewritingIt() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":9,"printedTerms":["ROUND END"],
                "keywords":["ROUND END","BONUS"],"externalDocumentDependencies":[],
                "ruleGroups":[{"identifier":"ROUND END",
                  "fact":"结算两次奖励，然后把标记移到第三格。",
                  "quantitySpans":["Score both bonuses","Score both bonuses","Move to space 3"]}],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain duplicate text");
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
    void classifiesEveryTeachingCatalogValidatorFailureAndAllowsRepeatedLabelsWithDifferentFacts() {
        assertTeachingViolation(
                () -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("not JSON"),
                TeachingCatalogRepairCode.MALFORMED_JSON);
        assertTeachingViolation(
                () -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                        {"pages":[{"pageNumber":1,"printedTerms":"SETUP","keywords":[],
                        "externalDocumentDependencies":[],"ruleGroups":[],
                        "ruleGroupInventoryComplete":true}]}
                        """),
                TeachingCatalogRepairCode.SCHEMA_MISMATCH);
        assertTeachingViolation(
                () -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                        {"pages":[{"pageNumber":1,"printedTerms":["MOVE"],"keywords":[],
                        "externalDocumentDependencies":[],"ruleGroups":[
                        {"identifier":"MOVE","fact":"Move one pawn.","quantitySpans":[]},
                        {"identifier":"MOVE","fact":"Move one pawn.","quantitySpans":[]}],
                        "ruleGroupInventoryComplete":true}]}
                        """),
                TeachingCatalogRepairCode.DUPLICATE_RULE_GROUP);

        CatalogDraft repeatedLabelWithDistinctFacts = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":1,"printedTerms":["MOVE"],"keywords":[],
                "externalDocumentDependencies":[],"ruleGroups":[
                {"identifier":"MOVE","fact":"Move one pawn.","quantitySpans":[]},
                {"identifier":"MOVE","fact":"Move a second pawn.","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """);
        assertThat(repeatedLabelWithDistinctFacts.pages().getFirst().ruleGroupFacts()).hasSize(2);

        CatalogRequest request = new CatalogRequest(
                List.of(new PageImageInput(1, "image/png", new byte[] {1})), "owner", "Example Game");
        assertTeachingViolation(
                () -> SpringAiVisualRulebookPageCatalogModel.normalizeTeachingPageBindings(
                        request,
                        new CatalogDraft(List.of(new PageSummary(
                                2, "SETUP", "Visible setup rule.", List.of("setup"))))),
                TeachingCatalogRepairCode.PAGE_BINDING_MISMATCH);
    }

    @Test
    void repairUsesOneFixedExhaustiveInstructionPerCodeWithoutRawOutputOrExceptionText() throws IOException {
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
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"pages":[{"pageNumber":1,"printedTerms":["SETUP"],"keywords":["setup"],
                "externalDocumentDependencies":[],"ruleGroups":[
                {"identifier":"SETUP","fact":"Place the board.","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);
        CatalogRequest request = new CatalogRequest(
                List.of(new PageImageInput(1, "image/png", png())),
                "owner",
                "Example Game");
        String rawFailure = "RAW_PROVIDER_OUTPUT_MUST_NOT_ENTER_REPAIR";

        for (TeachingCatalogRepairCode code : TeachingCatalogRepairCode.values()) {
            TeachingCatalogContractViolation violation =
                    new TeachingCatalogContractViolation(code, new IllegalArgumentException(rawFailure));
            model.repairTeachingCatalog(request, violation.repairCode());
        }

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(4)).call(prompts.capture());
        List<String> repairPrompts = prompts.getAllValues().stream()
                .map(prompt -> prompt.getInstructions().stream()
                        .map(message -> message.getText())
                        .collect(java.util.stream.Collectors.joining("\n")))
                .toList();
        assertThat(repairPrompts).noneMatch(text -> text.contains(rawFailure));
        assertThat(repairPrompts).anySatisfy(text -> assertThat(text).contains(
                "exactly one syntactically valid JSON object"));
        assertThat(repairPrompts).anySatisfy(text -> assertThat(text).contains(
                "exactly the declared root, page, rule-group, dependency, and quantity-span fields"));
        assertThat(repairPrompts).anySatisfy(text -> assertThat(text).contains(
                "each exact identifier-and-fact tuple at most once"));
        assertThat(repairPrompts).anySatisfy(text -> assertThat(text).contains(
                "copy its supplied pageNumber exactly"));
    }

    @Test
    void routesQwenPageFactsToTheFastStructuredVisualModel() throws IOException {
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
                        {"pages":[{"pageNumber":1,"printedTerms":["SETUP"],
                         "keywords":["setup","card"],
                         "externalDocumentDependencies":[],"ruleGroups":[{"identifier":"SETUP",
                         "fact":"Each player takes a card.","quantitySpans":[]}],
                         "ruleGroupInventoryComplete":true}]}
                        """)))));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        CatalogRequest request = new CatalogRequest(
                List.of(new PageImageInput(1, "image/png", png())), "owner", "Example Game");
        CatalogDraft teachingDraft = model.summarizeForTeaching(request);

        assertThat(teachingDraft.pages()).singleElement().satisfies(page -> {
            assertThat(page.ruleGroupIdentifiers()).containsExactly("page-1-group-1");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3-vl-flash");
        assertThat(options.getMaxTokens()).isEqualTo(4_800);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getTemperature()).isZero();
        List<String> instructions = prompt.getValue().getInstructions().stream()
                        .map(message -> message.getText().replaceAll("\\s+", " "))
                        .toList();
        assertThat(instructions)
                .anySatisfy(text -> assertThat(text).contains(
                "A direction to another guide, sheet, booklet, or document",
                "externalDocumentDependencies",
                "documentTitle",
                "missingCoverageTags",
                "quantitySpans",
                "do not return a separate quantityObservations array",
                "not as an executable rule on this page"));
        assertThat(instructions).anySatisfy(text -> assertThat(text).contains(
                "separately titled file",
                "return externalDocumentDependencies as an empty array"));
        assertThat(model.teachingStartupExecutionIdentity("owner")).hasValueSatisfying(identity -> {
            assertThat(identity.provider()).isEqualTo("qwen");
            assertThat(identity.model()).isEqualTo("qwen3-vl-flash");
        });

    }

    @Test
    void preservesConcreteOpenAiCompatibleOptionsForANonQwenVisualProvider() throws IOException {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel providerModel = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .model("vision-compatible-model")
                .build();
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                UUID.randomUUID(), ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        when(providerModel.getDefaultOptions()).thenReturn(defaults);
        when(providerModel.getOptions()).thenReturn(defaults);
        when(providerModel.call(any(Prompt.class))).thenReturn(response("""
                {"pages":[{"pageNumber":1,"printedTerms":["SETUP"],"keywords":["setup","card"],
                 "externalDocumentDependencies":[],"ruleGroups":[{"identifier":"SETUP",
                 "fact":"Each player takes one card.","quantitySpans":["one card"]}],
                 "ruleGroupInventoryComplete":true}]}
                """));
        ChatModel chatModel = new QuotaAwareChatModel(
                providerModel,
                quota,
                "owner",
                ModelAccountQuota.CredentialSource.PLATFORM,
                Role.VISUAL,
                "compatible",
                "vision-compatible-model",
                16_000,
                Clock.systemUTC());
        when(configuration.usesFake(Role.VISUAL, "owner")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "owner")).thenReturn(true);
        when(configuration.providerFor(Role.VISUAL, "owner")).thenReturn("compatible");
        when(configuration.modelFor(Role.VISUAL, "owner")).thenReturn(chatModel);
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        model.summarizeForTeaching(new CatalogRequest(
                List.of(new PageImageInput(1, "image/png", png())), "owner", "Example Game"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(providerModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions())
                .as("an OpenAI-compatible delegate must not receive generic DefaultChatOptions")
                .isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) prompt.getValue().getOptions()).getModel())
                .isEqualTo("vision-compatible-model");
    }

    @Test
    void returnsAnInvalidTeachingLedgerToTheAuditedCatalogWorkflowWithoutAHiddenRetry() throws IOException {
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
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"pages":[{"pageNumber":1,"printedTerms":["MOVE"],
                 "keywords":["move","pawn"],"externalDocumentDependencies":[],
                 "ruleGroups":[{"identifier":"MOVE","fact":"Move one pawn.",
                 "quantitySpans":[{"total":1,"originalSpan":"one pawn"}]}],
                 "ruleGroupInventoryComplete":true}]}
                """));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        assertThatThrownBy(() -> model.summarizeForTeaching(new CatalogRequest(
                        List.of(new PageImageInput(1, "image/png", png())), "owner", "Example Game")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantitySpans");
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void leavesAnInvalidMultiPageLedgerForTheCallerToSplitWithoutAQualityRetry() throws IOException {
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
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"pages":[
                 {"pageNumber":1,"printedTerms":["MOVE"],
                  "keywords":["move","pawn"],"externalDocumentDependencies":[],
                  "ruleGroups":[{"identifier":"MOVE","quantitySpans":[]}],
                  "ruleGroupInventoryComplete":true},
                 {"pageNumber":2,"printedTerms":["DRAW"],
                  "keywords":["draw","card"],"externalDocumentDependencies":[],
                  "ruleGroups":[{"identifier":"DRAW","fact":"Draw one card.","quantitySpans":[]}],
                  "ruleGroupInventoryComplete":true}]}
                """));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(1, "image/png", png()),
                        new PageImageInput(2, "image/png", png())),
                "owner",
                "Example Game");

        assertThatThrownBy(() -> model.summarizeForTeaching(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain the declared fields");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3-vl-flash");
        assertThat(options.getMaxTokens()).isEqualTo(4_800);
    }

    @Test
    void usesTheMeasuredFastQwenModelOnlyForTeachingStartupRequests() {
        assertThat(SpringAiVisualRulebookPageCatalogModel.teachingStartupModelName("qwen", "qwen3.6-plus"))
                .isEqualTo("qwen3-vl-flash");
        assertThat(SpringAiVisualRulebookPageCatalogModel.teachingStartupModelName("qwen", "qwen3-vl-flash"))
                .isEqualTo("qwen3-vl-flash");
        assertThat(SpringAiVisualRulebookPageCatalogModel.teachingStartupModelName("gemini", "gemini-2.5-flash"))
                .isEqualTo("gemini-2.5-flash");
    }

    @Test
    void completeTeachingCatalogKeepsExternalSourceDependenciesStructured() {
        String response = """
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY A CARD"],
                "keywords":["PLAY A CARD"],
                "externalDocumentDependencies":[
                  {"documentTitle":"First Session Booklet","missingCoverageTags":["setup"]},
                  {"documentTitle":"Reference Folio","missingCoverageTags":[]}],
                "ruleGroups":[{"identifier":"PLAY A CARD",
                  "fact":"当前玩家打出一张牌并执行行动。","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """;
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(response);

        assertThat(draft.pages()).singleElement().satisfies(page ->
                assertThat(page.sourceDependencies()).containsExactly(
                        new SourceDependency("First Session Booklet", List.of("setup")),
                        new SourceDependency("Reference Folio", List.of())));

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        response.replace(
                                "\"documentTitle\":\"First Session Booklet\"",
                                "\"documentTitle\":[\"First\",\"Session\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentTitle");
    }

    @Test
    void productionCatalogCompletenessComesOnlyFromStructuredRuleGroups() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE","BUILD"],"keywords":["MOVE","BUILD"],
                "externalDocumentDependencies":[],"ruleGroups":[
                  {"identifier":"MOVE","fact":"当前玩家移动一个棋子。","quantitySpans":[]},
                  {"identifier":"BUILD","fact":"当前玩家放置一座建筑。","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.ruleGroupIdentifiers()).containsExactly("page-4-group-1", "page-4-group-2");
            assertThat(page.ruleGroupFacts())
                    .extracting(fact -> fact.label(), fact -> fact.fact())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("MOVE", "当前玩家移动一个棋子。"),
                            org.assertj.core.groups.Tuple.tuple("BUILD", "当前玩家放置一座建筑。"));
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],"keywords":["MOVE","PAWN"],
                "externalDocumentDependencies":[],"ruleGroups":[{"identifier":"MOVE","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain the declared fields");
    }

    @Test
    void productionCatalogKeepsExactRuleEvidenceAcrossTheBoundedKeywordRange() {
        String ledger = """
                {"pages":[{"pageNumber":7,"printedTerms":["ΚΥΚΛΟΣ"],"keywords":%s,
                "externalDocumentDependencies":[],"ruleGroups":[
                  {"identifier":"ΚΥΚΛΟΣ","fact":"玩家执行这一项可见流程。","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """;

        var noKeywords = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                ledger.formatted("[]"));
        var oneKeyword = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                ledger.formatted("[\"ΚΥΚΛΟΣ\"]"));
        String sixteenKeywords = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(index -> "\"term-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var denseKeywords = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                ledger.formatted(sixteenKeywords));

        assertThat(noKeywords.pages()).singleElement().satisfies(page -> {
            assertThat(page.keywords()).containsExactly("page 7");
            assertThat(page.ruleGroupFacts()).singleElement();
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
        assertThat(oneKeyword.pages()).singleElement().satisfies(page -> {
            assertThat(page.keywords()).containsExactly("ΚΥΚΛΟΣ");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
        assertThat(denseKeywords.pages()).singleElement().satisfies(page -> {
            assertThat(page.keywords()).hasSize(16);
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void productionCatalogBoundsOptionalMetadataWithoutDiscardingValidatedRuleGroups() {
        String fifteenPrintedTerms = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(index -> "\"printed-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String twentyKeywords = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(index -> "\"term-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                        {"pages":[{"pageNumber":7,"printedTerms":%s,"keywords":%s,
                        "externalDocumentDependencies":[],"ruleGroups":[
                          {"identifier":"CYCLE","fact":"玩家执行这一项可见流程。","quantitySpans":[]}],
                        "ruleGroupInventoryComplete":true}]}
                        """.formatted(fifteenPrintedTerms, twentyKeywords));

        assertThat(accepted.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo(java.util.stream.IntStream.rangeClosed(1, 12)
                    .mapToObj(index -> "printed-" + index)
                    .collect(java.util.stream.Collectors.joining("; ")));
            assertThat(page.keywords()).containsExactlyElementsOf(
                    java.util.stream.IntStream.rangeClosed(1, 16).mapToObj(index -> "term-" + index).toList());
            assertThat(page.ruleGroupFacts()).singleElement().satisfies(fact -> {
                assertThat(fact.label()).isEqualTo("CYCLE");
                assertThat(fact.fact()).isEqualTo("玩家执行这一项可见流程。");
            });
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void productionCatalogDeduplicatesOptionalMetadataButKeepsItsTypeAndAbuseBoundaries() {
        String ledger = """
                {"pages":[{"pageNumber":7,"printedTerms":["CYCLE"," CYCLE ","TURN"],"keywords":%s,
                "externalDocumentDependencies":[],"ruleGroups":[
                  {"identifier":"CYCLE","fact":"玩家执行这一项可见流程。","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """;

        var deduplicated = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                ledger.formatted("[\"CYCLE\",\" CYCLE \",\"TURN\"]"));

        assertThat(deduplicated.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("CYCLE; TURN");
            assertThat(page.keywords()).containsExactly("CYCLE", "TURN");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        ledger.formatted("[\"CYCLE\",\" \"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywords must contain non-blank text");
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        ledger.formatted("[\"CYCLE\",7]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywords must contain non-blank text");

        String abusiveKeywords = java.util.stream.IntStream.rangeClosed(1, 65)
                .mapToObj(index -> "\"term-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        ledger.formatted(abusiveKeywords)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywords exceeds the absolute metadata item limit");
    }

    @Test
    void productionTeachingCatalogIgnoresUnconsumedFieldsButRejectsMissingInvalidAndDuplicateProtocolData() {
        String exact = """
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],"keywords":["MOVE","PAWN"],
                "externalDocumentDependencies":[],"ruleGroups":[
                  {"identifier":"MOVE","fact":"当前玩家移动一个棋子。","quantitySpans":[]}],
                "ruleGroupInventoryComplete":true}]}
                """;
        String additive = """
                {"statusLine":"done","pages":[{"pageNumber":4,"printedTerms":["MOVE"],
                "keywords":["MOVE","PAWN"],"pageNote":"ignored",
                "externalDocumentDependencies":[{"documentTitle":"Reference",
                  "missingCoverageTags":["setup"],"url":"ignored"}],
                "ruleGroups":[{"identifier":"MOVE","fact":"当前玩家移动一个棋子。",
                  "quantitySpans":[],"confidence":0.9}],"ruleGroupInventoryComplete":true}]}
                """;

        assertThat(SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(exact).pages())
                .singleElement();
        assertThat(SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(additive).pages())
                .singleElement();
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        exact.replace("\"keywords\":[\"MOVE\",\"PAWN\"],", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must contain the declared fields");
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        exact.replace(
                                "\"printedTerms\":[\"MOVE\"],",
                                "\"printedTerms\":\"MOVE\",\"factualSummary\":[],")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("printedTerms must be an array");
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        exact.replace(
                                "\"pageNumber\":4,",
                                "\"pageNumber\":4,\"pageNumber\":5,")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                        "```json\n" + exact + "\n```"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    private SpringAiVisualRulebookPageCatalogModel model(RuntimeModelConfiguration configuration) throws IOException {
        return new SpringAiVisualRulebookPageCatalogModel(
                configuration,
                new FakeVisualRulebookPageCatalogModel(),
                new ClassPathResource("prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt"),
                4_800);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
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

    private static void assertTeachingViolation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            TeachingCatalogRepairCode expectedCode) {
        assertThatThrownBy(invocation).isInstanceOfSatisfying(
                TeachingCatalogContractViolation.class,
                violation -> assertThat(violation.repairCode()).isEqualTo(expectedCode));
    }
}
