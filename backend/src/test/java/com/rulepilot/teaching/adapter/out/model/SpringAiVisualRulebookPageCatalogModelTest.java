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
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellVerificationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierReferencePage;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageTranscript;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
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
import org.springframework.ai.chat.messages.UserMessage;
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
                "Do not inventory icons, propose rectangles or coordinates",
                "Those belong to later enrichment")
                .doesNotContain(
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
    void pairedRuleGroupsProjectToTheExistingLedgerWithoutCrossArrayTextMatching() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":4,"printedTerms":["地板线","计分"],
                "factualSummary":["本页说明回合结束后的计分流程。"],"keywords":["地板线","计分"],
                "sourceDependencies":[],
                "ruleGroups":[
                  {"identifier":"地板线","fact":"回合结束时，地板线中的瓷砖按其位置扣分。"},
                  {"identifier":"如果刚放置的瓷砖的水平或垂直方向都没有直接相连的瓷砖",
                   "fact":"该瓷砖单独计 1 分。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[{"kind":"TOTAL_EXACT","pageNumber":4,"ruleGroupIndex":1,
                  "total":1,"originalSpan":"score 1 point"}]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.ruleGroupIdentifiers()).containsExactly(
                    "page-4-group-1",
                    "page-4-group-2");
            assertThat(page.factualSummary()).isEqualTo("""
                    本页说明回合结束后的计分流程。
                    page-4-group-1: [地板线] 回合结束时，地板线中的瓷砖按其位置扣分。
                    page-4-group-2: [如果刚放置的瓷砖的水平或垂直方向都没有直接相连的瓷砖] 该瓷砖单独计 1 分。""");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void pairedRuleGroupsRequireEachIdentifierAndFactButNotRepeatedPrefixText() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],"factualSummary":[],
                "keywords":["MOVE"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"MOVE"}],
                "ruleGroupInventoryComplete":true,"quantityObservations":[]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly identifier and fact");

        var repeatedHeading = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],"factualSummary":[],
                "keywords":["MOVE"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"MOVE","fact":"移动一个棋子。"},
                              {"identifier":" move ","fact":"再次移动一个棋子。"}],
                "ruleGroupInventoryComplete":true,"quantityObservations":[]}]}
                """);

        assertThat(repeatedHeading.pages().getFirst().ruleGroupIdentifiers())
                .containsExactly("page-4-group-1", "page-4-group-2");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":4,"printedTerms":["MOVE"],"factualSummary":[],
                "keywords":["MOVE"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"MOVE","fact":"移动一个棋子。"},
                              {"identifier":" move ","fact":" 移动一个棋子。 "}],
                "ruleGroupInventoryComplete":true,"quantityObservations":[]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly duplicated");
    }

    @Test
    void discriminatedQuantityKindsProjectToTheExistingDomainRoles() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":19,"printedTerms":["K#2"],"factualSummary":[],
                "keywords":["K#2"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"K#2","fact":"在每个活跃缺口放置一个碎片，但缺口总数在图中无法可靠确认。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[{"kind":"REQUIRES_PAGE_INSPECTION","pageNumber":19,
                  "ruleGroupIndex":0,"variantAxis":"active notches",
                  "originalSpan":"one shard at every active notch"}]}]}
                """);

        assertThat(draft.pages().getFirst().quantityObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.UNRESOLVED);
            assertThat(observation.resolution()).isEqualTo(QuantityResolution.REQUIRES_PAGE_INSPECTION);
        });

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":19,"printedTerms":["K#2"],"factualSummary":[],
                "keywords":["K#2"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"K#2","fact":"在每个活跃缺口放置一个碎片。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[{"kind":"REQUIRES_PAGE_INSPECTION","pageNumber":19,
                  "ruleGroupIndex":0,"variantAxis":"active notches","total":1,
                  "originalSpan":"one shard at every active notch"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain only its exact fields");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":19,"printedTerms":["K#2"],"factualSummary":[],
                "keywords":["K#2"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"K#2","fact":"该页显示一个可确定的总数。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[{"kind":"TOTAL_EXACT","pageNumber":19,"ruleGroupIndex":1,
                  "total":2,"originalSpan":"total 2"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleGroupIndex must identify one ruleGroups item");
    }

    @Test
    void discriminatedQuantityKindCannotExpressTheProductionCrossFieldFailure() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":19,"printedTerms":["K#2"],"factualSummary":[],
                "keywords":["K#2"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"K#2","fact":"在每个活跃缺口放置一个碎片，但缺口总数在图中无法可靠确认。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[{"kind":"REQUIRES_PAGE_INSPECTION","pageNumber":19,
                  "ruleGroupIndex":0,"variantAxis":"active notches",
                  "originalSpan":"one shard at every active notch"}]}]}
                """);

        assertThat(draft.pages().getFirst().quantityObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.UNRESOLVED);
            assertThat(observation.resolution()).isEqualTo(QuantityResolution.REQUIRES_PAGE_INSPECTION);
            assertThat(observation.variantCount()).isNull();
            assertThat(observation.perVariantQuantity()).isNull();
            assertThat(observation.derivedTotal()).isNull();
        });
    }

    @Test
    void perVariantQuantityDerivesItsTotalInsteadOfRepeatingModelArithmetic() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":7,"printedTerms":["R-Delta"],"factualSummary":[],
                "keywords":["R-Delta"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"R-Delta","fact":"四种颜色各放置一个棱柱。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[{"kind":"PER_VARIANT_EXACT","pageNumber":7,
                  "ruleGroupIndex":0,"variantAxis":"colors","variantCount":4,"perVariantQuantity":1,
                  "originalSpan":"4 colors x 1 prism each"}]}]}
                """);

        assertThat(draft.pages().getFirst().quantityObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.PER_VARIANT);
            assertThat(observation.variantCount()).isEqualTo(4);
            assertThat(observation.perVariantQuantity()).isEqualTo(1);
            assertThat(observation.derivedTotal()).isEqualTo(4);
            assertThat(observation.resolution()).isEqualTo(QuantityResolution.EXACT);
        });
    }

    @Test
    void discriminatedQuantityParserExtractsProvidedObservationsWithoutLexicalContentPolicing() {
        var partial = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":16,"printedTerms":["RESULT"],"factualSummary":[],
                "keywords":["RESULT"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"RESULT",
                  "fact":"完成四个目标为普通成功，五个为出色，六个为卓越，七个为惊艳。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":4,"originalSpan":"4 Fair success"},
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":5,"originalSpan":"5 Impressive success"}]}]}
                """);
        assertThat(partial.pages().getFirst().quantityObservations()).hasSize(2);

        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":16,"printedTerms":["RESULT"],"factualSummary":[],
                "keywords":["RESULT"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"RESULT",
                  "fact":"三个或更少为失败，四个为普通成功，五个为出色，六个为卓越，七个为惊艳。"}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":3,"originalSpan":"3 or fewer Failure"},
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":4,"originalSpan":"4 Fair success"},
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":5,"originalSpan":"5 Impressive success"},
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":6,"originalSpan":"6 Tremendous success"},
                  {"kind":"TOTAL_EXACT","pageNumber":16,"ruleGroupIndex":0,
                   "total":7,"originalSpan":"7 Stunning success"}]}]}
                """);

        assertThat(accepted.pages().getFirst().quantityObservations()).hasSize(5);
    }

    @Test
    void nonQuantitativeRuleGroupsStillAllowAnEmptyObservationArray() {
        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV5("""
                {"pages":[{"pageNumber":8,"printedTerms":["PASS"],"factualSummary":[],
                "keywords":["PASS"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"PASS","fact":"完成行动后，把回合交给下一位玩家。"}],
                "ruleGroupInventoryComplete":true,"quantityObservations":[]}]}
                """);

        assertThat(accepted.pages().getFirst().quantityObservations()).isEmpty();
    }

    @Test
    void literalQuantitySpansPreserveRangesAndTableThresholdsWithoutModelArithmetic() {
        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":12,"printedTerms":["PLAYERS","RESULT"],"factualSummary":[],
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
                {"pages":[{"pageNumber":12,"printedTerms":["PLAYERS"],"factualSummary":[],
                "keywords":["PLAYERS"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"PLAYERS","fact":"该模式支持1至4名玩家，时长80分钟。",
                 "quantitySpans":["1-4 PLAYERS"]}],
                "ruleGroupInventoryComplete":true}]}
                """);
        assertThat(partialEvidence.pages().getFirst().quantityObservations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.originalSpan()).isEqualTo("1-4 PLAYERS"));
    }

    @Test
    void literalQuantitySpansCollapseExactDuplicatesWithoutDroppingDistinctEvidence() {
        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":9,"printedTerms":["ROUND END"],"factualSummary":[],
                "keywords":["ROUND END"],"externalDocumentDependencies":[],
                "ruleGroups":[{"identifier":"ROUND END",
                  "fact":"结算两次奖励，然后把标记移到第三格。",
                  "quantitySpans":["Score both bonuses","Score both bonuses","Move to space 3"]}],
                "ruleGroupInventoryComplete":true}]}
                """);

        assertThat(accepted.pages().getFirst().quantityObservations())
                .extracting(observation -> observation.originalSpan())
                .containsExactly("Score both bonuses", "Move to space 3");
    }

    @Test
    void literalQuantitySpansAcceptOnlyAnExactRedundantGroupIndexWithoutPolicingTheirMeaning() {
        var accepted = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":1,"printedTerms":["SETUP"],"factualSummary":[],
                "keywords":["SETUP"],"sourceDependencies":[],
                "ruleGroups":[
                  {"identifier":"SETUP","fact":"每轮招募一名专家。","ruleGroupIndex":0},
                  {"identifier":"GOAL","fact":"满足目标即可得分。","ruleGroupIndex":1}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[
                  {"pageNumber":1,"ruleGroupIndex":0,"originalSpan":"每轮招募一名专家"},
                  {"pageNumber":1,"ruleGroupIndex":1,"originalSpan":"1-4 PLAYERS"}]}]}
                """);

        assertThat(accepted.pages().getFirst().quantityObservations())
                .extracting(observation -> observation.ruleGroupIdentifier())
                .containsExactly("page-1-group-1", "page-1-group-2");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6("""
                {"pages":[{"pageNumber":1,"printedTerms":["SETUP"],"factualSummary":[],
                "keywords":["SETUP"],"sourceDependencies":[],
                "ruleGroups":[{"identifier":"SETUP","fact":"每轮招募一名专家。","ruleGroupIndex":1}],
                "ruleGroupInventoryComplete":true,
                "quantityObservations":[
                  {"pageNumber":1,"ruleGroupIndex":0,"originalSpan":"每轮招募一名专家"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redundant ruleGroupIndex");
    }

    @Test
    void progressiveTeachingPromptKeepsFactsAndSourceDependenciesInsideTheirDurableContracts() throws IOException {
        String prompt = new ClassPathResource(
                        "prompts/visual-page-progressive-teaching-start-v4-source-contract-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains(
                "must fit within 4,000 Unicode characters",
                "never rely on text beyond that limit",
                "title as one non-empty string",
                "never return an array or a shortened title",
                "must always be an explicit array with no duplicates",
                "quantityObservations",
                "originalSpan",
                "REQUIRES_PAGE_INSPECTION",
                "ruleGroupCoverage",
                "LEGAL_ACTION",
                "NECESSARY_EXCEPTION");
    }

    @Test
    void mapsOpaqueQuantityFixturesWithoutFlatteningPerVariantScopeOrRawEvidence() throws IOException {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                new ClassPathResource("evaluation/visual-quantity-observations-v1.json").getInputStream());

        var explicit = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3(
                root.path("cases").get(0).path("response").toString());
        var observation = explicit.pages().getFirst().quantityObservations().getFirst();
        var evidence = observation.evidenceText();

        assertThat(observation.pageNumber()).isEqualTo(7);
        assertThat(observation.ruleGroupIdentifier()).isEqualTo("R-Δ");
        assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.PER_VARIANT);
        assertThat(observation.variantAxis()).isEqualTo("glyph families");
        assertThat(observation.variantCount()).isEqualTo(4);
        assertThat(observation.perVariantQuantity()).isEqualTo(1);
        assertThat(observation.derivedTotal()).isEqualTo(4);
        assertThat(observation.originalSpan()).isEqualTo("4 glyph families × 1 prism each");
        assertThat(observation.resolution()).isEqualTo(QuantityResolution.EXACT);
        assertThat(evidence).contains(
                "page=7",
                "ruleGroup=R-Δ",
                "scope=PER_VARIANT",
                "variantAxis=glyph families",
                "variantCount=4",
                "perVariantQuantity=1",
                "derivedTotal=4",
                "resolution=EXACT",
                "originalSpan=4 glyph families × 1 prism each");

        var ambiguous = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3(
                root.path("cases").get(1).path("response").toString());
        var ambiguousObservation = ambiguous.pages().getFirst().quantityObservations().getFirst();
        var ambiguousEvidence = ambiguousObservation.evidenceText();

        assertThat(ambiguousObservation.quantifierScope()).isEqualTo(QuantifierScope.UNRESOLVED);
        assertThat(ambiguousObservation.variantAxis()).isEqualTo("active notches");
        assertThat(ambiguousObservation.derivedTotal()).isNull();
        assertThat(ambiguousObservation.originalSpan()).isEqualTo("one shard at every active notch");
        assertThat(ambiguousObservation.resolution()).isEqualTo(QuantityResolution.REQUIRES_PAGE_INSPECTION);
        assertThat(ambiguousEvidence)
                .contains(
                        "page=19",
                        "ruleGroup=K#2",
                        "scope=UNRESOLVED",
                        "resolution=REQUIRES_PAGE_INSPECTION",
                        "originalSpan=one shard at every active notch",
                        "inspect the cited page; no total was inferred")
                .doesNotContain("derivedTotal=1");
    }

    @Test
    void rejectsUnsafeQuantityArithmeticInsteadOfPublishingAGuessedTotal() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3("""
                {"pages":[{"pageNumber":7,"printedTerms":["R-Δ"],
                "factualSummary":["R-Δ: Place the visibly specified prisms."],"keywords":["R-Δ","prism"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["R-Δ"],"ruleGroupInventoryComplete":true,
                "quantityObservations":[{"pageNumber":7,"ruleGroupIdentifier":"R-Δ",
                  "quantifierScope":"PER_VARIANT","variantAxis":"glyph families","variantCount":4,
                  "perVariantQuantity":1,"derivedTotal":1,
                  "originalSpan":"4 glyph families × 1 prism each","resolution":"EXACT"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived total");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3("""
                {"pages":[{"pageNumber":19,"printedTerms":["K#2"],
                "factualSummary":["K#2: Follow the visible placement relation."],"keywords":["K#2","shard"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["K#2"],"ruleGroupInventoryComplete":true,
                "quantityObservations":[{"pageNumber":19,"ruleGroupIdentifier":"K#2",
                  "quantifierScope":"UNRESOLVED","variantAxis":"active notches","variantCount":null,
                  "perVariantQuantity":null,"derivedTotal":1,
                  "originalSpan":"one shard at every active notch",
                  "resolution":"REQUIRES_PAGE_INSPECTION"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot publish a derived total");
    }

    @Test
    void v3QuantityObservationsStayBoundToTheirExactPageAndRuleGroup() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3("""
                {"pages":[{"pageNumber":7,"printedTerms":["R-Δ"],
                "factualSummary":["R-Δ: Place one prism for each glyph family."],"keywords":["R-Δ","prism"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["R-Δ"],"ruleGroupInventoryComplete":true,
                "quantityObservations":[{"pageNumber":8,"ruleGroupIdentifier":"OTHER",
                  "quantifierScope":"PER_VARIANT","variantAxis":"glyph families","variantCount":4,
                  "perVariantQuantity":1,"derivedTotal":4,
                  "originalSpan":"4 glyph families × 1 prism each","resolution":"EXACT"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page and rule group");
    }

    @Test
    void v3QuantityObservationRejectsFieldsOutsideTheVersionedContract() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3("""
                {"pages":[{"pageNumber":7,"printedTerms":["R-Δ"],
                "factualSummary":["R-Δ: Place one prism for each glyph family."],"keywords":["R-Δ","prism"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["R-Δ"],"ruleGroupInventoryComplete":true,
                "quantityObservations":[{"pageNumber":7,"ruleGroupIdentifier":"R-Δ",
                  "quantifierScope":"PER_VARIANT","variantAxis":"glyph families","variantCount":4,
                  "perVariantQuantity":1,"derivedTotal":4,
                  "originalSpan":"4 glyph families × 1 prism each","resolution":"EXACT",
                  "guessedUnit":"prism"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact fields");
    }

    @Test
    void legacyV2TeachingReplayStillParsesWithoutInventingQuantityObservations() {
        var replay = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["PLAY: The player takes one visible action."],"keywords":["PLAY"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["PLAY"],
                "ruleGroupInventoryComplete":true}]}
                """);

        assertThat(replay.pages()).singleElement().satisfies(page -> assertThat(page.quantityObservations()).isEmpty());
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV3("""
                {"pages":[{"pageNumber":4,"printedTerms":["PLAY"],
                "factualSummary":["PLAY: The player takes one visible action."],"keywords":["PLAY"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["PLAY"],
                "ruleGroupInventoryComplete":true}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantityObservations");
    }

    @Test
    void progressiveV3SelectedPageKeepsItsExactQuantityObservation() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStartV3("""
                {"pageSketches":[
                  {"pageNumber":12,"role":"GAMEPLAY_RULES","visibleHeading":"M-β",
                   "visibleTerms":["M-β"],"coverageTags":["setup"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":12,"printedTerms":["M-β","rings"],
                  "factualSummary":["M-β: Place two rings for each of three visible lanes."],
                  "keywords":["M-β","rings"],
                  "quantityObservations":[{"pageNumber":12,"ruleGroupIdentifier":"M-β",
                    "quantifierScope":"PER_VARIANT","variantAxis":"lanes","variantCount":3,
                    "perVariantQuantity":2,"derivedTotal":6,
                    "originalSpan":"3 lanes × 2 rings each","resolution":"EXACT"}]}}
                """);

        assertThat(draft.selectedPageFacts().quantityObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.pageNumber()).isEqualTo(12);
            assertThat(observation.variantAxis()).isEqualTo("lanes");
            assertThat(observation.variantCount()).isEqualTo(3);
            assertThat(observation.perVariantQuantity()).isEqualTo(2);
            assertThat(observation.derivedTotal()).isEqualTo(6);
            assertThat(observation.originalSpan()).isEqualTo("3 lanes × 2 rings each");
        });
    }

    @Test
    void progressiveV4ClassifiesEveryOpaqueRuleGroupForTheSourceCoverageContract() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStartV4("""
                {"pageSketches":[
                  {"pageNumber":12,"role":"GAMEPLAY_RULES","visibleHeading":"T-0",
                   "visibleTerms":["T-0","A-1","A-2","E-0"],
                   "coverageTags":["core_loop","source_coverage"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[],
                   "ruleGroupCoverage":[
                     {"identifier":"T-0","role":"CORE_LOOP"},
                     {"identifier":"A-1","role":"LEGAL_ACTION"},
                     {"identifier":"A-2","role":"LEGAL_ACTION"},
                     {"identifier":"E-0","role":"NECESSARY_EXCEPTION"}]}],
                 "selectedPageFacts":{"pageNumber":12,
                  "printedTerms":["T-0","A-1","A-2","E-0"],
                  "factualSummary":[
                    "T-0: The visible cycle advances.",
                    "A-1: The current actor may take the first visible branch.",
                    "A-2: The current actor may take the second visible branch.",
                    "E-0: The visible condition changes the second branch."],
                  "keywords":["T-0","A-1"],"quantityObservations":[]}}
                """);

        assertThat(draft.pages().getFirst().ruleGroupCoverage())
                .extracting(
                        com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupCoverage::identifier,
                        com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupCoverage::role)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("T-0", SourceCoverageRole.CORE_LOOP),
                        org.assertj.core.groups.Tuple.tuple("A-1", SourceCoverageRole.LEGAL_ACTION),
                        org.assertj.core.groups.Tuple.tuple("A-2", SourceCoverageRole.LEGAL_ACTION),
                        org.assertj.core.groups.Tuple.tuple("E-0", SourceCoverageRole.NECESSARY_EXCEPTION));
    }

    @Test
    void progressiveV4PreservesPartialRoleClassificationWithoutRejectingThePage() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStartV4("""
                {"pageSketches":[
                  {"pageNumber":12,"role":"GAMEPLAY_RULES","visibleHeading":"T-0",
                   "visibleTerms":["T-0","A-1"],"coverageTags":["core_loop"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[],
                   "ruleGroupCoverage":[{"identifier":"T-0","role":"CORE_LOOP"}]}],
                 "selectedPageFacts":{"pageNumber":12,"printedTerms":["T-0","A-1"],
                  "factualSummary":["T-0: The visible cycle advances.","A-1: A visible branch is available."],
                  "keywords":["T-0","A-1"],"quantityObservations":[]}}
                """);

        assertThat(draft.pages().getFirst().visibleTerms()).containsExactly("T-0", "A-1");
        assertThat(draft.pages().getFirst().ruleGroupCoverage())
                .extracting(com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupCoverage::identifier)
                .containsExactly("T-0");
    }

    @Test
    void progressiveV4RequiresAnExplicitRuleGroupRoleInventoryOnEveryPage() {
        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStartV4("""
                {"pageSketches":[
                  {"pageNumber":12,"role":"GAMEPLAY_RULES","visibleHeading":"T-0",
                   "visibleTerms":["T-0"],"coverageTags":["core_loop"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":12,"printedTerms":["T-0"],
                  "factualSummary":["T-0: The visible cycle advances."],
                  "keywords":["T-0","cycle"],"quantityObservations":[]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleGroupCoverage for every page");
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
    void transcribesAVisualRulebookPageWithTheDedicatedOcrModelAndNoSystemOrJsonContract() throws IOException {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder().model("qwen3.7-plus").build();
        when(configuration.usesFake(Role.VISUAL, "owner")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "owner")).thenReturn(true);
        when(configuration.providerFor(Role.VISUAL, "owner")).thenReturn("qwen");
        when(configuration.modelFor(Role.VISUAL, "owner")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("玩家分数：9、13、16\n门槛：33"));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        PageTranscript transcript = model.transcribeTeachingPage(
                new PageImageInput(16, "image/png", png()), "owner");

        assertThat(transcript.pageNumber()).isEqualTo(16);
        assertThat(transcript.text()).isEqualTo("玩家分数：9、13、16\n门槛：33");
        assertThat(model.teachingPageTranscriptionExecutionIdentity("owner"))
                .hasValueSatisfying(identity -> {
                    assertThat(identity.provider()).isEqualTo("qwen");
                    assertThat(identity.model()).isEqualTo("qwen3.5-ocr");
                });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).singleElement().isInstanceOf(UserMessage.class);
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).contains(
                "Copy all visible text from PDF page 16",
                "Write ? for a character that cannot be read reliably");
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.5-ocr");
        assertThat(options.getTemperature()).isEqualTo(0.01);
        assertThat(options.getResponseFormat()).isNull();
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void keepsAnOcrTranscriptBoundToItsExactPageInTheSemanticCatalogPrompt() throws IOException {
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
                {"pages":[{"pageNumber":16,"printedTerms":"最终计分","keywords":["计分"],
                 "externalDocumentDependencies":[],"ruleGroups":[{"identifier":"合作模式范例",
                 "fact":"玩家分数为9、13、16，门槛为33。","quantitySpans":["9、13、16","33"]}],
                 "ruleGroupInventoryComplete":true}]}
                """));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        model.summarizeForTeaching(new CatalogRequest(
                List.of(new PageImageInput(16, "image/png", png())),
                "owner",
                "Example Game",
                List.of(new PageTranscript(16, "玩家分数：9、13、16\n门槛：33"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().stream()
                        .map(message -> message.getText().replaceAll("\\s+", " "))
                        .toList())
                .anySatisfy(text -> assertThat(text).contains(
                        "--- PDF page 16 ---",
                        "玩家分数：9、13、16",
                        "门槛：33",
                        "Do not replace transcript digits with a calculated or inferred value"));
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
                         "keywords":["setup"],
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
        assertThat(options.getModel()).isEqualTo("qwen3.7-plus");
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
            assertThat(identity.model()).isEqualTo("qwen3.7-plus");
        });

        model.summarize(request);

        ArgumentCaptor<Prompt> allPrompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(allPrompts.capture());
        OpenAiChatOptions completeAuditOptions =
                (OpenAiChatOptions) allPrompts.getAllValues().getLast().getOptions();
        assertThat(completeAuditOptions.getModel()).isEqualTo("qwen3.7-plus");
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
                {"pages":[{"pageNumber":1,"printedTerms":"SETUP","keywords":["setup"],
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
    void repairsAnInvalidTeachingLedgerOnceWithTheConfiguredQualityModel() throws IOException {
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
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("""
                        {"pages":[{"pageNumber":1,"printedTerms":["MOVE"],
                         "factualSummary":[],"keywords":["move"],"sourceDependencies":[],
                         "ruleGroups":[{"identifier":"MOVE","fact":"Move one pawn.",
                         "quantitySpans":[{"total":1,"originalSpan":"one pawn"}]}],
                         "ruleGroupInventoryComplete":true}]}
                        """),
                response("""
                        {"pages":[{"pageNumber":1,"printedTerms":["MOVE"],
                         "factualSummary":[],"keywords":["move"],"sourceDependencies":[],
                         "ruleGroups":[{"identifier":"MOVE","fact":"Move one pawn.",
                         "quantitySpans":["one pawn"]}],
                         "ruleGroupInventoryComplete":true}]}
                        """));
        SpringAiVisualRulebookPageCatalogModel model = model(configuration);

        CatalogDraft repaired = model.summarizeForTeaching(new CatalogRequest(
                List.of(new PageImageInput(1, "image/png", png())), "owner", "Example Game"));

        assertThat(repaired.pages()).singleElement().satisfies(page -> {
            assertThat(page.factualSummary()).isEqualTo("page-1-group-1: [MOVE] Move one pawn.");
            assertThat(page.quantityObservations()).singleElement().satisfies(observation -> {
                assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.LITERAL_SOURCE_SPAN);
                assertThat(observation.resolution()).isEqualTo(QuantityResolution.TRANSCRIBED_SOURCE_SPAN);
            });
        });
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        OpenAiChatOptions initialOptions = (OpenAiChatOptions) prompts.getAllValues().getFirst().getOptions();
        OpenAiChatOptions repairOptions = (OpenAiChatOptions) prompts.getAllValues().getLast().getOptions();
        assertThat(initialOptions.getModel()).isEqualTo("qwen3.7-plus");
        assertThat(initialOptions.getMaxTokens()).isEqualTo(4_800);
        assertThat(repairOptions.getModel()).isEqualTo("qwen3.7-plus");
        assertThat(repairOptions.getMaxTokens()).isEqualTo(4_800);
        assertThat(prompts.getAllValues().getLast().getInstructions().stream()
                        .map(message -> message.getText().replaceAll("\\s+", " "))
                        .toList())
                .anySatisfy(text -> assertThat(text).contains(
                        "The previous ledger failed deterministic contract validation",
                        "each visible rule group as one ruleGroups object",
                        "identifier, its same-page fact, and quantitySpans",
                        "Every identifier must be unique within its page",
                        "shortest exact visible opening phrase for each later group",
                        "never return a separate ruleGroupIdentifiers or quantityObservations array",
                        "Do not return kind",
                        "must bind every such value through one or more strings in its own quantitySpans",
                        "Never calculate a replacement total",
                        "Detected deterministic issue:",
                        "quantitySpans must contain text"));
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
                  "factualSummary":[],"keywords":["move"],"sourceDependencies":[],
                  "ruleGroups":[{"identifier":"MOVE"}],
                  "ruleGroupInventoryComplete":true,"quantityObservations":[]},
                 {"pageNumber":2,"printedTerms":["DRAW"],
                  "factualSummary":[],"keywords":["draw"],"sourceDependencies":[],
                  "ruleGroups":[{"identifier":"DRAW","fact":"Draw one card."}],
                  "ruleGroupInventoryComplete":true,"quantityObservations":[]}]}
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
                .hasMessageContaining("exactly identifier and fact");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.7-plus");
        assertThat(options.getMaxTokens()).isEqualTo(4_800);
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
    void progressiveTeachingStartUsesConfiguredQwenAndKeepsExactPageRolesSeparateFromSelectedFacts() throws IOException {
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
                           "sourceDependencies":[],"ruleGroupCoverage":[]},
                          {"pageNumber":2,"role":"GAMEPLAY_RULES","visibleHeading":"Setup",
                           "visibleTerms":["market","veggie cards"],"coverageTags":["setup"],
                           "ruleGroupInventoryComplete":true,"sourceDependencies":[],
                           "ruleGroupCoverage":[
                             {"identifier":"market","role":"SETUP"},
                             {"identifier":"veggie cards","role":"SETUP"}]},
                          {"pageNumber":3,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                           "visibleTerms":["take cards","refill","end marker","score line"],
                           "coverageTags":["core_loop","end","scoring"],
                           "ruleGroupInventoryComplete":true,"sourceDependencies":[],
                           "ruleGroupCoverage":[
                             {"identifier":"take cards","role":"LEGAL_ACTION"},
                             {"identifier":"refill","role":"CORE_LOOP"},
                             {"identifier":"end marker","role":"ENDING"},
                             {"identifier":"score line","role":"SCORING"}]}],
                         "selectedPageFacts":{"pageNumber":2,
                           "printedTerms":["market","veggie cards"],
                           "factualSummary":[
                             "market：按可见关系摆放市场。",
                             "veggie cards：按可见关系准备牌。"],
                           "keywords":["market","veggie cards"],"quantityObservations":[]}}
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
        assertThat(result.pages().get(0).ruleGroupCoverage()).isEmpty();
        assertThat(result.pages().get(1).ruleGroupCoverage())
                .extracting(coverage -> coverage.role())
                .containsOnly(SourceCoverageRole.SETUP);
        assertThat(result.pages().get(2).ruleGroupCoverage())
                .extracting(coverage -> coverage.role())
                .containsExactly(
                        SourceCoverageRole.LEGAL_ACTION,
                        SourceCoverageRole.CORE_LOOP,
                        SourceCoverageRole.ENDING,
                        SourceCoverageRole.SCORING);
        assertThat(result.selectedPageFacts()).satisfies(facts -> {
            assertThat(facts.pageNumber()).isEqualTo(2);
            assertThat(facts.factualSummary()).contains("摆放市场", "准备牌");
            assertThat(facts.visualAnchors()).isEmpty();
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.7-plus");
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
                        "quantityObservations",
                        "REQUIRES_PAGE_INSPECTION",
                        "ruleGroupCoverage",
                        "LEGAL_ACTION",
                        "NECESSARY_EXCEPTION",
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
    void progressiveTeachingStartPreservesARequiredFactBeyondTheHistoricalStorageLimit() {
        String oversizedFact = "CONTEXT: " + "x".repeat(4_050);
        String response = """
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Available action",
                   "visibleTerms":["LATE"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["LATE"],
                  "factualSummary":["%s","LATE: Apply the visible late rule."],"keywords":["LATE"]}}
                """.formatted(oversizedFact);

        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart(response);

        assertThat(draft.selectedPageFacts().factualSummary())
                .contains(oversizedFact, "LATE: Apply the visible late rule.")
                .hasSizeGreaterThan(4_000);
    }

    @Test
    void progressiveTeachingStartPreservesEveryDensePageRuleGroup() {
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

        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart(response);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.visibleTerms()).hasSize(9);
            assertThat(page.visibleTerms()).contains("GROUP_1", "GROUP_9");
        });
        assertThat(draft.selectedPageFacts().factualSummary())
                .contains("GROUP_1: visible fact 1.", "GROUP_9: visible fact 9.");
    }

    @Test
    void progressiveTeachingStartDoesNotRejectAnEmptyOptionalInventoryLocally() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":4,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":[],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":[]}],
                 "selectedPageFacts":{"pageNumber":4,"printedTerms":["TURN"],
                  "factualSummary":["TURN: A visible turn relation."],"keywords":["TURN"]}}
                """);

        assertThat(draft.pages().getFirst().visibleTerms()).isEmpty();
        assertThat(draft.pages().getFirst().ruleGroupInventoryComplete()).isTrue();
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
                  "factualSummary":["take action: 当前玩家执行一个行动。"],"keywords":["take action","turn"]}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDependencies");

        assertThatThrownBy(() -> SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":1,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":["take action"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,"sourceDependencies":null}],
                 "selectedPageFacts":{"pageNumber":1,"printedTerms":["take action"],
                  "factualSummary":["take action: 当前玩家执行一个行动。"],"keywords":["take action","turn"]}}
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
    void progressiveTeachingStartPreservesModelOwnedMissingResponsibilities() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseProgressiveTeachingStart("""
                {"pageSketches":[
                  {"pageNumber":1,"role":"GAMEPLAY_RULES","visibleHeading":"Turn",
                   "visibleTerms":["take action"],"coverageTags":["core_loop","end","scoring"],
                   "ruleGroupInventoryComplete":true,
                   "sourceDependencies":[{"title":"Extra Notes","missingCoverageTags":["table_feel"]}]}],
                 "selectedPageFacts":{"pageNumber":1,"printedTerms":["take action"],
                  "factualSummary":["take action: 当前玩家执行一个行动。"],"keywords":["take action","turn"]}}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page ->
                assertThat(page.sourceDependencies()).containsExactly(
                        new SourceDependency("Extra Notes", List.of("table_feel"))));
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
    void sourceDependencyPreservesANaturallyLongTitle() {
        String title = "Guide " + "x".repeat(160);

        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                teachingCatalogWithDependency(
                        "{\"title\":\"" + title + "\",\"missingCoverageTags\":[\"setup\"]}"));

        assertThat(draft.pages()).singleElement().satisfies(page ->
                assertThat(page.sourceDependencies()).containsExactly(
                        new SourceDependency(title, List.of("setup"))));
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
    void sourceDependencyPreservesEveryExplicitMissingResponsibility() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(
                teachingCatalogWithDependency(
                        "{\"title\":\"Session Guide\",\"missingCoverageTags\":["
                                + "\"setup\",\"core_loop\",\"end\",\"scoring\",\"table_feel\"]}"));

        assertThat(draft.pages()).singleElement().satisfies(page ->
                assertThat(page.sourceDependencies()).containsExactly(new SourceDependency(
                        "Session Guide", List.of("setup", "core_loop", "end", "scoring", "table_feel"))));
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
    void completeTeachingCatalogPreservesARequiredFactInsteadOfRejectingOrTruncatingIt() {
        String oversizedFact = "CONTEXT: " + "x".repeat(4_050);
        String response = """
                {"pages":[{"pageNumber":4,"printedTerms":["LATE"],
                "factualSummary":["%s","LATE: Apply the visible late rule."],"keywords":["LATE"],
                "sourceDependencies":[],"ruleGroupIdentifiers":["LATE"],
                "ruleGroupInventoryComplete":true}]}
                """.formatted(oversizedFact);

        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(response);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.factualSummary()).contains(oversizedFact, "LATE: Apply the visible late rule.");
            assertThat(page.factualSummary()).hasSizeGreaterThan(4_000);
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
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
    void completeTeachingCatalogPreservesEveryValidDensePageRuleGroup() {
        String identifiers = java.util.stream.IntStream.rangeClosed(1, 33)
                .mapToObj(index -> "\"GROUP_" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String facts = java.util.stream.IntStream.rangeClosed(1, 33)
                .mapToObj(index -> "\"GROUP_" + index + ": visible fact " + index + ".\"")
                .collect(java.util.stream.Collectors.joining(","));
        String response = """
                {"pages":[{"pageNumber":4,"printedTerms":["GROUPS"],
                "factualSummary":[%s],"keywords":["GROUPS"],
                "sourceDependencies":[],"ruleGroupIdentifiers":[%s],
                "ruleGroupInventoryComplete":true}]}
                """.formatted(facts, identifiers);

        var draft = SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalog(response);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.ruleGroupIdentifiers()).hasSize(33);
            assertThat(page.ruleGroupIdentifiers()).contains("GROUP_1", "GROUP_33");
            assertThat(page.factualSummary()).contains(
                    "GROUP_1: visible fact 1.", "GROUP_33: visible fact 33.");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
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
                new ClassPathResource("prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt"),
                new ClassPathResource("prompts/visual-page-progressive-teaching-start-v4-source-contract-system.txt"),
                new ClassPathResource("prompts/visual-icon-localization-v2-system.txt"),
                new ClassPathResource("prompts/visual-icon-crop-review-v4-system.txt"),
                new ClassPathResource("prompts/visual-identifier-cell-v1-system.txt"),
                new ClassPathResource("prompts/visual-identifier-reference-match-v1-system.txt"),
                "qwen3.5-ocr",
                4_800);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
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
    void preservesLongOptionalTextInsteadOfSilentlyTruncatingTheIconPage() {
        String longSummary = "visible rule ".repeat(300);
        String json = "{\"pages\":[{\"pageNumber\":6,\"printedTerms\":\"ICONS\","
                + "\"factualSummary\":" + jsonString(longSummary) + ","
                + "\"keywords\":[\""
                + "x".repeat(180) + "\"],\"iconOccurrences\":[],\"iconInventoryComplete\":true}]}";

        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog(json);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.factualSummary()).isEqualTo(longSummary.strip());
            assertThat(page.keywords().getFirst()).isEqualTo("x".repeat(180));
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
                        "TURN: Place one marker for each of two visible bands.",
                        List.of("turn", "bands"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        List.of("TURN"),
                        true,
                        List.of(new com.rulepilot.teaching.VisualQuantityObservation(
                                111,
                                "TURN",
                                QuantifierScope.PER_VARIANT,
                                "bands",
                                2,
                                1,
                                2,
                                "2 bands × 1 marker each",
                                QuantityResolution.EXACT)))));

        CatalogDraft normalized =
                SpringAiVisualRulebookPageCatalogModel.normalizeTeachingPageBindings(request, draft);

        assertThat(normalized.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(11);
            assertThat(page.ruleGroupIdentifiers()).containsExactly("TURN");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
            assertThat(page.quantityObservations()).singleElement().satisfies(observation -> {
                assertThat(observation.pageNumber()).isEqualTo(11);
                assertThat(observation.originalSpan()).isEqualTo("2 bands × 1 marker each");
            });
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

    @Test
    void rejectsMissingDuplicateOrCrossPageOcrTranscriptBindings() {
        List<PageImageInput> pages = List.of(
                new PageImageInput(3, "image/jpeg", new byte[] {3}),
                new PageImageInput(4, "image/jpeg", new byte[] {4}));

        assertThatThrownBy(() -> new CatalogRequest(
                        pages,
                        "owner",
                        "Example",
                        List.of(new PageTranscript(3, "page three"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bind every requested page exactly once");
        assertThatThrownBy(() -> new CatalogRequest(
                        pages,
                        "owner",
                        "Example",
                        List.of(new PageTranscript(3, "first"), new PageTranscript(3, "duplicate"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bind every requested page exactly once");
        assertThat(new CatalogRequest(
                        pages,
                        "owner",
                        "Example",
                        List.of(new PageTranscript(3, "page three"), new PageTranscript(4, "page four")))
                .transcripts())
                .extracting(PageTranscript::pageNumber)
                .containsExactly(3, 4);
    }

    private static String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
