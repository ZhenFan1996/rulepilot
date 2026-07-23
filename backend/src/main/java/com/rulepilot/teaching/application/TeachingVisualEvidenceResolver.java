package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded optional visual-page evidence enrichment for one planned teaching section. */
final class TeachingVisualEvidenceResolver {

    private static final Logger log = LoggerFactory.getLogger(TeachingVisualEvidenceResolver.class);

    private final AssistantReadTools tools;
    private final AuditedAgentInvocations invocations;
    private final VisualRulebookPageFacts visualFacts;
    private final VisualRulebookPageCatalogModel visualCatalog;

    TeachingVisualEvidenceResolver(
            AssistantReadTools tools,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookPageCatalogModel visualCatalog) {
        this.tools = tools;
        this.invocations = invocations;
        this.visualFacts = visualFacts;
        this.visualCatalog = visualCatalog;
    }

    List<RuleEvidence> resolve(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved,
            UUID assistantRunId) {
        boolean visualPlaceholder = TeachingVisualEvidenceSelector.hasVisualPageEvidence(retrieved);
        if (!visualPlaceholder || planned.sourcePageNumbers().isEmpty()) return retrieved;
        try {
            List<RuleEvidence> pageEvidence = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    operationName("readRuleEvidencePages", planned.position()),
                    planned.sourcePageNumbers().size(),
                    "Planner-selected visual rulebook pages retrieved",
                    () -> tools.readRuleEvidencePages(
                            plan.documentVersionId(), new LinkedHashSet<>(planned.sourcePageNumbers()), true),
                    this::evidenceTokens);
            if (!pageEvidence.isEmpty()) {
                log.info(
                        "Teaching topic {} is bound to visual source pages {}",
                        planned.topicKey(),
                        planned.sourcePageNumbers());
                List<RuleEvidence> enriched = enrichPageFacts(plan.documentVersionId(), pageEvidence, List.of());
                if (!TeachingVisualEvidenceSelector.hasVisualPageEvidence(enriched)
                        || !visualCatalog.available(plan.createdBy())) {
                    return enriched;
                }
                return enrichRequiredPageFacts(plan, planned, pageEvidence, enriched, assistantRunId);
            }
        } catch (RuntimeException failure) {
            log.warn("Visual page-bound evidence read failed for topic {}: {}", planned.topicKey(), failure.getMessage());
        }
        return retrieved;
    }

    private List<RuleEvidence> enrichRequiredPageFacts(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> pageEvidence,
            List<RuleEvidence> enriched,
            UUID assistantRunId) {
        Map<Integer, AssistantReadTools.RulePageImage> images = new LinkedHashMap<>();
        pageEvidence.stream()
                .filter(TeachingVisualEvidenceSelector::isVisualPageEvidence)
                .flatMap(source -> source.pageImages().stream())
                .forEach(image -> images.putIfAbsent(image.pageNumber(), image));
        List<VisualRulebookPageFacts.PageFact> interpreted = new ArrayList<>();
        for (AssistantReadTools.RulePageImage image : images.values()) {
            try {
                var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                        List.of(new PageImageInput(image.pageNumber(), image.mediaType(), image.content())),
                        plan.createdBy(),
                        plan.gameTitle());
                var catalog = invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        "inspectRequiredVisualPage|" + planned.position() + "|" + image.pageNumber(),
                        800,
                        "Required visual rulebook page interpreted for grounded teaching",
                        () -> visualCatalog.summarize(request),
                        result -> estimateTokens(result.toString()));
                interpreted.addAll(catalog.pages().stream()
                        .map(summary -> new VisualRulebookPageFacts.PageFact(
                                summary.pageNumber(),
                                summary.printedTerms(),
                                summary.factualSummary(),
                                summary.keywords(),
                                summary.visualAnchors()))
                        .toList());
            } catch (RuntimeException failure) {
                log.warn(
                        "Required visual page interpretation failed for topic {} page {}: {}",
                        planned.topicKey(),
                        image.pageNumber(),
                        failure.getMessage());
            }
        }
        if (interpreted.isEmpty()) return enriched;
        visualFacts.merge(plan.documentVersionId(), interpreted);
        log.info(
                "Teaching topic {} added on-demand visual facts for pages {}",
                planned.topicKey(),
                interpreted.stream().map(VisualRulebookPageFacts.PageFact::pageNumber).toList());
        return enrichPageFacts(plan.documentVersionId(), pageEvidence, interpreted);
    }

    private List<RuleEvidence> enrichPageFacts(
            UUID documentVersionId,
            List<RuleEvidence> evidence,
            List<VisualRulebookPageFacts.PageFact> supplementalFacts) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toUnmodifiableSet());
        if (pages.isEmpty()) return evidence;
        Map<Integer, String> factsByPage = Stream.concat(
                        visualFacts.find(documentVersionId, pages).stream(), supplementalFacts.stream())
                .collect(Collectors.toUnmodifiableMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::evidenceText,
                        (existing, supplied) -> supplied));
        if (factsByPage.isEmpty()) return evidence;
        return evidence.stream()
                .map(source -> {
                    String facts = source.pageFrom() == source.pageTo() ? factsByPage.get(source.pageFrom()) : null;
                    if (facts == null || !TeachingVisualEvidenceSelector.isVisualPageEvidence(source)) return source;
                    return new RuleEvidence(
                            source.chunkId(),
                            source.documentVersionId(),
                            source.sectionType(),
                            source.heading(),
                            facts,
                            source.pageFrom(),
                            source.pageTo(),
                            source.pageImages());
                })
                .toList();
    }

    private int evidenceTokens(List<RuleEvidence> evidence) {
        return evidence.stream().mapToInt(source -> estimateTokens(source.excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
