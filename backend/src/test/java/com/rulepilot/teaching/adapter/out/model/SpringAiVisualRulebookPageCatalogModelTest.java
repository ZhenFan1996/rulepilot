package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringAiVisualRulebookPageCatalogModelTest {

    @Test
    void acceptsAdditiveFieldsEmptyKeywordsAndOptionalQuantitySpans() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parse("""
                {"trace":"ignored","pages":[
                  {"pageNumber":9,"keywords":[],"ruleGroups":[
                    {"identifier":"Repairing","fact":"Spend an action to repair.",
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
    void discardsOnlyMalformedOptionalRuleGroupsAndKeepsReadablePageFacts() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parse("""
                {"pages":[{"pageNumber":2,"printedTerms":["Turn order"],"ruleGroups":[
                  {"identifier":"turn"},
                  {"identifier":"action","fact":"Take one action."}
                ]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("Turn order");
            assertThat(page.ruleGroupFacts()).singleElement().satisfies(group ->
                    assertThat(group.identifier()).isEqualTo("action"));
        });
    }
}
