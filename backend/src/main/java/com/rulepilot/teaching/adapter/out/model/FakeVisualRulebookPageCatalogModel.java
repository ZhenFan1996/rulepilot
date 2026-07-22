package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeVisualRulebookPageCatalogModel implements VisualRulebookPageCatalogModel {

    @Override
    public boolean available(String modelConfigurationOwner) {
        return false;
    }

    @Override
    public CatalogDraft summarize(CatalogRequest request) {
        return new CatalogDraft(request.pages().stream()
                .map(page -> new PageSummary(
                        page.pageNumber(),
                        "Page " + page.pageNumber(),
                        "Rendered rulebook page; visual model configuration is unavailable.",
                        List.of("page " + page.pageNumber())))
                .toList());
    }
}
