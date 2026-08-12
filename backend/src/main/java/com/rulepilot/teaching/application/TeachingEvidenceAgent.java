package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolEvidenceHandles;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Acquires missing chapter evidence through native read tools without composing or publishing lesson prose. */
@Service
@Profile("!test")
public class TeachingEvidenceAgent implements TeachingEvidenceRefiner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingEvidenceAgent.class);
    private static final int MAX_EVIDENCE_PER_SECTION = 10;
    private static final int MAX_EVIDENCE_CHUNKS_PER_PAGE = 3;
    private static final int MAX_OBSERVED_EVIDENCE = 24;
    private static final String SYSTEM_PROMPT = """
            You refine evidence for one board-game teaching chapter. Never write the chapter and never use rule
            knowledge outside supplied evidence and tool observations. The validated lesson plan already identifies
            source pages that are missing from the current chapter evidence. On the first turn, call read_rule_pages
            with only the listed missing validated source pages (at most five). Do not translate the teaching objective
            into a new search query, broaden the topic, change scope, or invent a page. Return exactly EVIDENCE_READY
            after the page observation. Your prose is never evidence.
            """;

    private final NativeToolAgent nativeAgent;
    private final NativeToolScopes scopes;
    private final AssistantReadTools tools;
    private final EvidenceVerifier evidenceVerifier;

    public TeachingEvidenceAgent(
            NativeToolAgent nativeAgent,
            NativeToolScopes scopes,
            AssistantReadTools tools,
            EvidenceVerifier evidenceVerifier) {
        this.nativeAgent = nativeAgent;
        this.scopes = scopes;
        this.tools = tools;
        this.evidenceVerifier = evidenceVerifier;
    }

    @Override
    public TeachingSectionEvidenceRetriever.Result refine(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            TeachingSectionEvidenceRetriever.Result deterministic) {
        // A progressive visual plan has already read and verified its exact source page directly. Asking the native
        // evidence agent to interpret the model-authored chapter objective would add no canonical evidence and can
        // only extend the first-section critical path.
        if (ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)) return deterministic;
        if (!TeachingEvidenceRefinementPolicy.requiresRefinement(planned, deterministic)) return deterministic;
        var scope = scopes.create(plan.createdBy(), plan.documentVersionId(), assistantRunId);
        if (scope.isEmpty()) return deterministic;
        List<Integer> missingSourcePages = missingSourcePages(planned, deterministic.evidence());
        if (missingSourcePages.isEmpty()) return deterministic;

        NativeToolAgent.RunResult result;
        if (!nativeAgent.supports(Role.TEACHING, plan.createdBy())) return deterministic;
        try {
            result = nativeAgent.run(new RunRequest(
                    Role.TEACHING,
                    scope.get(),
                    SYSTEM_PROMPT,
                    chapterRequest(planned, deterministic.evidence(), missingSourcePages),
                    "EVIDENCE_REFINEMENT_UNAVAILABLE",
                    1,
                    384,
                    Set.of("read_rule_pages"),
                    Set.of("read_rule_pages"),
                    1));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Teaching evidence refinement failed for section {}; preserving deterministic evidence",
                    planned.position());
            return deterministic;
        }
        int totalToolCalls = deterministic.toolCalls() + result.toolCalls();
        if (result.status() != RunStatus.COMPLETED || result.toolCalls() == 0) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        Set<UUID> observedIds = NativeToolEvidenceHandles.prioritized(result, MAX_OBSERVED_EVIDENCE);
        if (observedIds.isEmpty()) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        return mergeCanonicalEvidence(
                plan.documentVersionId(), deterministic, observedIds, Set.copyOf(missingSourcePages), totalToolCalls);
    }

    private TeachingSectionEvidenceRetriever.Result mergeCanonicalEvidence(
            UUID documentVersionId,
            TeachingSectionEvidenceRetriever.Result deterministic,
            Set<UUID> observedIds,
            Set<Integer> allowedPages,
            int totalToolCalls) {
        List<RuleEvidence> hydrated;
        try {
            hydrated = tools.readRuleEvidenceIds(documentVersionId, observedIds);
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn("Observed teaching evidence could not be hydrated; preserving deterministic evidence");
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        Map<UUID, RuleEvidence> hydratedById = hydrated.stream()
                .filter(source -> documentVersionId.equals(source.documentVersionId()))
                .filter(source -> observedIds.contains(source.chunkId()))
                .filter(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                        .anyMatch(allowedPages::contains))
                .collect(java.util.stream.Collectors.toMap(
                        RuleEvidence::chunkId,
                        source -> source,
                        (first, duplicate) -> first));
        Map<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        deterministic.evidence().forEach(source -> merged.put(source.chunkId(), source));
        List<RuleEvidence> prioritized = new ArrayList<>();
        for (UUID observedId : observedIds) {
            RuleEvidence source = hydratedById.get(observedId);
            if (source == null) continue;
            RuleEvidence existing = merged.get(observedId);
            if (existing != null && !sameEvidence(existing, source)) {
                return new TeachingSectionEvidenceRetriever.Result(
                        List.of(), totalToolCalls, TeachingSectionEvidenceRetriever.State.INVALID);
            }
            merged.put(observedId, source);
            prioritized.add(source);
        }
        if (prioritized.isEmpty()) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        deterministic.evidence().stream()
                .filter(source -> !observedIds.contains(source.chunkId()))
                .forEach(prioritized::add);
        List<RuleEvidence> selected = selectPageDiverseEvidence(prioritized);
        boolean verified = evidenceVerifier.verify(new VerificationRequest(
                        documentVersionId,
                        selected.stream().map(this::verifierEvidence).toList(),
                        List.of()))
                .verified();
        return verified
                ? new TeachingSectionEvidenceRetriever.Result(
                        selected, totalToolCalls, TeachingSectionEvidenceRetriever.State.VERIFIED)
                : new TeachingSectionEvidenceRetriever.Result(
                        List.of(), totalToolCalls, TeachingSectionEvidenceRetriever.State.INVALID);
    }

    private List<RuleEvidence> selectPageDiverseEvidence(List<RuleEvidence> prioritized) {
        Map<String, Integer> chunksPerPage = new LinkedHashMap<>();
        List<RuleEvidence> selected = new ArrayList<>();
        for (RuleEvidence source : prioritized) {
            String pageKey = source.pageFrom() + ":" + source.pageTo();
            int pageCount = chunksPerPage.getOrDefault(pageKey, 0);
            if (pageCount >= MAX_EVIDENCE_CHUNKS_PER_PAGE) continue;
            selected.add(source);
            chunksPerPage.put(pageKey, pageCount + 1);
            if (selected.size() == MAX_EVIDENCE_PER_SECTION) break;
        }
        return List.copyOf(selected);
    }

    private List<Integer> missingSourcePages(
            TeachingPlan.PlannedSection planned, List<RuleEvidence> evidence) {
        Set<Integer> evidencedPages = evidence.stream()
                .flatMap(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo()).boxed())
                .collect(java.util.stream.Collectors.toSet());
        return planned.sourcePageNumbers().stream()
                .filter(page -> !evidencedPages.contains(page))
                .distinct()
                .limit(5)
                .toList();
    }

    private String chapterRequest(
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            List<Integer> missingSourcePages) {
        StringBuilder request = new StringBuilder("Chapter title: ")
                .append(bounded(planned.title(), 240))
                .append("\nObjective: ")
                .append(bounded(planned.objective(), 700))
                .append("\nCoverage tags: ")
                .append(bounded(String.join(", ", planned.coverageTags()), 400))
                .append("\nMissing validated source pages: ")
                .append(missingSourcePages)
                .append("\nCurrent verified evidence:");
        if (evidence.isEmpty()) request.append(" none");
        for (RuleEvidence source : evidence.stream().limit(8).toList()) {
            request.append("\n- ")
                    .append(source.chunkId())
                    .append(" | ")
                    .append(bounded(source.heading(), 160))
                    .append(" | pages ")
                    .append(source.pageFrom())
                    .append('-')
                    .append(source.pageTo())
                    .append(" | ")
                    .append(bounded(source.excerpt(), 500));
        }
        return request.toString();
    }

    private boolean sameEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.excerpt().equals(second.excerpt())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo();
    }

    private EvidenceSource verifierEvidence(RuleEvidence source) {
        return new EvidenceSource(
                source.chunkId(),
                source.documentVersionId(),
                source.sectionType(),
                source.excerpt(),
                source.pageFrom(),
                source.pageTo());
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
