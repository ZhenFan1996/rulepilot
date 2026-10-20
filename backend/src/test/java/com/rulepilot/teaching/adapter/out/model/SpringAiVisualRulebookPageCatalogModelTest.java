package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringAiVisualRulebookPageCatalogModelTest {

    @Test
    void preservesSourceResolutionWhenTransportingDenseRulePages() throws Exception {
        var configuration = org.mockito.Mockito.mock(com.rulepilot.modelconfig.RuntimeModelConfiguration.class);
        var chat = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var role = com.rulepilot.modelconfig.RuntimeModelConfiguration.Role.VISUAL;
        org.mockito.Mockito.when(configuration.supportsVision(role, "player")).thenReturn(true);
        org.mockito.Mockito.when(configuration.modelFor(role, "player")).thenReturn(chat);
        org.mockito.Mockito.when(configuration.providerFor(role, "player")).thenReturn("qwen");
        org.mockito.Mockito.when(chat.getOptions()).thenReturn(
                org.springframework.ai.openai.OpenAiChatOptions.builder().model("vision-test").build());
        org.mockito.Mockito.when(chat.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(
                                "{\"pages\":[{\"pageNumber\":2,\"ruleGroups\":[]}]}")))));
        var source = new java.awt.image.BufferedImage(2400, 1800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(source, "png", encoded);
        byte[] original = encoded.toByteArray();
        var model = new SpringAiVisualRulebookPageCatalogModel(configuration,
                new FakeVisualRulebookPageCatalogModel(),
                new org.springframework.core.io.ByteArrayResource("Read only visible rules.".getBytes()));

        model.summarizeForTeaching(new CatalogRequest(
                List.of(new PageImageInput(2, "image/png", original)), "player"));

        var prompt = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        org.mockito.Mockito.verify(chat).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getMedia()).singleElement().satisfies(media -> {
            assertThat(media.getMimeType().toString()).isEqualTo("image/png");
            assertThat(media.getDataAsByteArray()).isEqualTo(original);
        });
    }

    @Test
    void acceptsAdditiveFieldsEmptyKeywordsAndOptionalQuantitySpans() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parse("""
                {"trace":"ignored","pages":[
                  {"pageNumber":9,"keywords":[],"ruleGroups":[
                    {"identifier":"Repairing","status":"READABLE","sourceKind":"PRINTED_TEXT","fact":"Spend an action to repair.",
                     "quantitySpans":["one action"],"futureField":true}
                  ],"futurePageField":{"ignored":true}}
                ]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(9);
            assertThat(page.keywords()).isEmpty();
            assertThat(page.ruleGroupFacts()).singleElement().satisfies(fact ->
                    assertThat(fact.identifier()).isEqualTo("Repairing"));
        });
    }

    @Test
    void acceptsAReadablePageWithNoRuleGroupsAndOwnsAttachmentIdentity() {
        var parsed = SpringAiVisualRulebookPageCatalogModel.parse("""
                {"pages":[{"pageNumber":999,"printedTerms":["Player aid"],"ruleGroups":[]}]}
                """);
        var request = new CatalogRequest(
                List.of(new PageImageInput(3, "image/png", new byte[] {1})), null);

        var bound = SpringAiVisualRulebookPageCatalogModel.normalizeTeachingPageBindings(request, parsed);

        assertThat(bound.pages().getFirst().pageNumber()).isEqualTo(3);
        assertThat(bound.pages().getFirst().ruleGroupFacts()).isEmpty();
    }

    @Test
    void omitsUncertainOrUnclassifiedAssociationsWithoutLosingReadableEvidence() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parse("""
                {"pages":[{"pageNumber":2,"printedTerms":["Turn order"],
                "factualSummary":"Unclassified claim must not bypass evidence status.","ruleGroups":[
                  {"identifier":"turn"},
                  {"identifier":"legend","status":"READABLE","sourceKind":"GRAPHICAL_ASSOCIATION","fact":"Unverified diagram value 99."},
                  {"identifier":"unclassified","fact":"An unchecked claim."},
                  {"identifier":"action","status":"READABLE","sourceKind":"PRINTED_TEXT","fact":"Take one action."}
                ]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("Turn order");
            assertThat(page.factualSummary()).contains("Take one action.", "Reader uncertainty", "legend")
                    .doesNotContain("Unverified diagram value 99.", "unchecked claim", "bypass");
            assertThat(page.ruleGroupFacts()).singleElement().satisfies(group ->
                    assertThat(group.identifier()).isEqualTo("action"));
        });
    }
}
