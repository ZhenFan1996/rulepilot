package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded optional visual-page evidence enrichment for one planned teaching section. */
final class TeachingVisualEvidenceResolver {

    private static final Logger log = LoggerFactory.getLogger(TeachingVisualEvidenceResolver.class);

    private final AssistantReadTools tools;
    private final AuditedAgentInvocations invocations;
    private final VisualRulebookPageFacts visualFacts;
    private final VisualRulebookCataloger visualCataloger;

    TeachingVisualEvidenceResolver(
            AssistantReadTools tools,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookCataloger visualCataloger) {
        this.tools = tools;
        this.invocations = invocations;
        this.visualFacts = visualFacts;
        this.visualCataloger = visualCataloger;
    }

    Resolution resolve(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved,
            UUID assistantRunId) {
        if (!requiresPageRead(plan, planned, retrieved)) return Resolution.unread(retrieved);
        Set<Integer> requestedPages = Set.copyOf(planned.sourcePageNumbers());
        ensurePageFacts(plan, requestedPages, assistantRunId);
        boolean completeFacts = hasCompleteFacts(plan.documentVersionId(), requestedPages);
        Map<UUID, RuleEvidence> pageEvidenceById = new LinkedHashMap<>();
        mergePageEvidence(pageEvidenceById, matchingPageEvidence(plan.documentVersionId(), requestedPages, retrieved));
        List<Set<Integer>> batches = pageReadBatches(planned.sourcePageNumbers());
        int toolCalls = 0;
        boolean completeRead = true;
        for (Set<Integer> batch : batches) {
            toolCalls++;
            try {
                List<RuleEvidence> batchEvidence = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        batchOperationName(
                                operationName("readRuleEvidencePages", planned.position()), toolCalls, batches.size()),
                        batch.size(),
                        completeFacts
                                ? "Planner-selected durable page evidence retrieved"
                                : "Planner-selected visual rulebook pages retrieved",
                        () -> tools.readRuleEvidencePages(plan.documentVersionId(), batch, !completeFacts),
                        this::evidenceTokens);
                if (CanonicalPageObservation.complete(
                                assistantRunId, plan.documentVersionId(), batch, batchEvidence)
                        .isEmpty()) {
                    completeRead = false;
                }
                mergePageEvidence(pageEvidenceById, batchEvidence);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                completeRead = false;
                log.warn(
                        "Visual page-bound evidence read failed for topic {} batch {}: {}",
                        planned.topicKey(),
                        toolCalls,
                        failure.getMessage());
            }
        }
        List<RuleEvidence> pageEvidence = List.copyOf(pageEvidenceById.values());
        Optional<CanonicalPageObservation> canonical = completeRead
                ? CanonicalPageObservation.complete(
                        assistantRunId,
                        plan.documentVersionId(),
                        Set.copyOf(planned.sourcePageNumbers()),
                        pageEvidence)
                : Optional.empty();
        if (canonical.isPresent()) {
            log.info(
                    "Teaching topic {} is bound to visual source pages {}",
                    planned.topicKey(),
                    planned.sourcePageNumbers());
            List<RuleEvidence> enriched = enrichPageFacts(plan.documentVersionId(), pageEvidence);
            return new Resolution(enriched, canonical, toolCalls);
        }
        if (!pageEvidence.isEmpty()) {
            log.warn(
                    "Visual page-bound evidence for topic {} was incomplete; retaining every safely observed page",
                    planned.topicKey());
            Map<UUID, RuleEvidence> retained = new LinkedHashMap<>();
            mergePageEvidence(retained, retrieved);
            pageEvidence.stream()
                    .filter(source -> plan.documentVersionId().equals(source.documentVersionId()))
                    .filter(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(planned.sourcePageNumbers()::contains))
                    .forEach(source -> mergePageEvidence(retained, List.of(source)));
            List<RuleEvidence> partial = List.copyOf(retained.values());
            List<RuleEvidence> enriched = enrichPageFacts(plan.documentVersionId(), partial);
            return new Resolution(enriched, Optional.empty(), toolCalls);
        }
        return new Resolution(retrieved, Optional.empty(), toolCalls);
    }

    static boolean requiresPageRead(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved) {
        if (plan == null || planned == null || retrieved == null || planned.sourcePageNumbers().isEmpty()) {
            return false;
        }
        return TeachingVisualEvidenceSelector.hasVisualPageEvidence(retrieved);
    }

    static int maximumPageReadToolCalls(TeachingPlan.PlannedSection planned) {
        if (planned == null) return 0;
        return pageReadBatches(planned.sourcePageNumbers()).size();
    }

    static List<Set<Integer>> pageReadBatches(List<Integer> pageNumbers) {
        if (pageNumbers == null || pageNumbers.isEmpty()) return List.of();
        List<Integer> ordered = pageNumbers.stream().distinct().toList();
        List<Set<Integer>> batches = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += DocumentPageImages.MAX_PAGES_PER_READ) {
            batches.add(Set.copyOf(ordered.subList(
                    start, Math.min(start + DocumentPageImages.MAX_PAGES_PER_READ, ordered.size()))));
        }
        return List.copyOf(batches);
    }

    static int estimatedCatalogModelCalls(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) return 0;
        // One page has one catalog owner regardless of how many chapters cite it. This sizes the ordinary useful
        // path only; corrections continue under the durable token/deadline/cancellation owner and are not a call cap.
        long uniquePages = plan.sections().stream()
                .flatMap(section -> section.sourcePageNumbers().stream())
                .distinct()
                .count();
        return Math.toIntExact(uniquePages);
    }

    record Resolution(
            List<RuleEvidence> evidence,
            Optional<CanonicalPageObservation> canonicalPageObservation,
            int toolCalls) {
        Resolution {
            evidence = List.copyOf(evidence);
            canonicalPageObservation = canonicalPageObservation == null
                    ? Optional.empty()
                    : canonicalPageObservation;
            if (toolCalls < 0) {
                throw new IllegalArgumentException("visual evidence resolution tool count is invalid");
            }
        }

        static Resolution unread(List<RuleEvidence> evidence) {
            return new Resolution(evidence, Optional.empty(), 0);
        }
    }

    record CanonicalPageObservation(
            UUID assistantRunId,
            UUID documentVersionId,
            Set<Integer> requestedPages,
            List<RuleEvidence> evidence) {
        CanonicalPageObservation {
            if (assistantRunId == null || documentVersionId == null || requestedPages == null || evidence == null) {
                throw new IllegalArgumentException("canonical teaching page observation is incomplete");
            }
            Set<Integer> immutablePages = Set.copyOf(requestedPages);
            List<RuleEvidence> immutableEvidence = List.copyOf(evidence);
            if (immutablePages.isEmpty()
                    || immutablePages.stream().anyMatch(page -> page == null || page < 1)
                    || immutableEvidence.isEmpty()
                    || immutableEvidence.stream().anyMatch(source -> !documentVersionId.equals(source.documentVersionId())
                            || java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                                    .noneMatch(immutablePages::contains))
                    || immutablePages.stream().anyMatch(page -> immutableEvidence.stream().noneMatch(source ->
                            page >= source.pageFrom() && page <= source.pageTo()))) {
                throw new IllegalArgumentException("canonical teaching page observation is incomplete");
            }
            requestedPages = immutablePages;
            evidence = immutableEvidence;
        }

        static Optional<CanonicalPageObservation> complete(
                UUID assistantRunId,
                UUID documentVersionId,
                Set<Integer> requestedPages,
                List<RuleEvidence> evidence) {
            try {
                return Optional.of(new CanonicalPageObservation(
                        assistantRunId, documentVersionId, requestedPages, evidence));
            } catch (IllegalArgumentException incomplete) {
                return Optional.empty();
            }
        }
    }

    private void mergePageEvidence(
            Map<UUID, RuleEvidence> evidenceById,
            List<RuleEvidence> batchEvidence) {
        for (RuleEvidence supplied : batchEvidence) {
            RuleEvidence existing = evidenceById.get(supplied.chunkId());
            if (existing == null) {
                evidenceById.put(supplied.chunkId(), supplied);
                continue;
            }
            if (!samePageEvidence(existing, supplied)) {
                throw new IllegalStateException("page evidence changed between bounded reads");
            }
            Map<Integer, RulePageImage> images = new LinkedHashMap<>();
            existing.pageImages().forEach(image -> images.putIfAbsent(image.pageNumber(), image));
            supplied.pageImages().forEach(image -> images.putIfAbsent(image.pageNumber(), image));
            evidenceById.put(
                    existing.chunkId(),
                    new RuleEvidence(
                            existing.chunkId(),
                            existing.documentVersionId(),
                            existing.sectionType(),
                            existing.heading(),
                            existing.excerpt(),
                            existing.pageFrom(),
                            existing.pageTo(),
                            List.copyOf(images.values()),
                            existing.contentKind()));
        }
    }

    private boolean samePageEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.excerpt().equals(second.excerpt())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo()
                && first.contentKind() == second.contentKind();
    }

    private void ensurePageFacts(TeachingPlan plan, Set<Integer> requestedPages, UUID assistantRunId) {
        try {
            visualCataloger.ensureTeachingPageFacts(
                    plan.documentVersionId(),
                    requestedPages,
                    sourcePageTotal(plan),
                    plan.gameTitle(),
                    plan.createdBy(),
                    assistantRunId);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            log.warn(
                    "Teaching page-fact owner could not complete pages for topic {}; retaining source evidence: {}",
                    plan.id(),
                    failure.getMessage());
        }
    }

    private boolean hasCompleteFacts(UUID documentVersionId, Set<Integer> requestedPages) {
        Set<Integer> completePages = visualFacts.find(documentVersionId, requestedPages).stream()
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .map(VisualRulebookPageFacts.PageFact::pageNumber)
                .collect(Collectors.toSet());
        return completePages.containsAll(requestedPages);
    }

    private static List<RuleEvidence> matchingPageEvidence(
            UUID documentVersionId, Set<Integer> requestedPages, List<RuleEvidence> evidence) {
        return evidence.stream()
                .filter(source -> documentVersionId.equals(source.documentVersionId()))
                .filter(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                        .anyMatch(requestedPages::contains))
                .toList();
    }

    private static int sourcePageTotal(TeachingPlan plan) {
        return plan.sections().stream()
                .flatMap(section -> section.sourcePageNumbers().stream())
                .mapToInt(Integer::intValue)
                .max()
                .orElseThrow(() -> new IllegalArgumentException("Teaching plan has no source pages"));
    }

    private List<RuleEvidence> enrichPageFacts(
            UUID documentVersionId,
            List<RuleEvidence> evidence) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toUnmodifiableSet());
        if (pages.isEmpty()) return evidence;
        Map<Integer, String> factsByPage = visualFacts.find(documentVersionId, pages).stream()
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .collect(Collectors.toUnmodifiableMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::factualSummary,
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
                            source.pageImages(),
                            RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
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

    private String batchOperationName(String operation, int batchNumber, int batchCount) {
        return batchCount == 1 ? operation : operation + "|" + batchNumber;
    }
}
