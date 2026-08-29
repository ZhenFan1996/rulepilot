package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

final class VisualRulebookCatalogerTestFixture {

    private VisualRulebookCatalogerTestFixture() {}

    static VisualRulebookCataloger unavailable(
            AssistantReadTools tools,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts facts) {
        return create(tools, invocations, facts, VisualRulebookPageCatalogModel.unavailable());
    }

    static VisualRulebookCataloger create(
            AssistantReadTools tools,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts facts,
            VisualRulebookPageCatalogModel model) {
        DocumentPageImages images = (documentVersionId, pageNumbers) -> tools
                .readRuleEvidencePages(documentVersionId, pageNumbers, true).stream()
                .flatMap(source -> source.pageImages().stream())
                .filter(image -> pageNumbers.contains(image.pageNumber()))
                .collect(Collectors.toMap(
                        RulePageImage::pageNumber,
                        image -> new DocumentPageImages.PageImage(
                                image.pageNumber(),
                                image.mediaType(),
                                image.content(),
                                image.width(),
                                image.height()),
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
        return new VisualRulebookCataloger(
                images,
                model,
                facts,
                invocations,
                Duration.ofSeconds(45),
                                10,
                4);
    }
}
