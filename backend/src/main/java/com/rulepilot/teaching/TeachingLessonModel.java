package com.rulepilot.teaching;

import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole;
import java.util.List;
import java.util.UUID;

public interface TeachingLessonModel {

    default String providerId() {
        return "unspecified";
    }

    default boolean supportsVisualEvidence() {
        return false;
    }

    /**
     * Visual capability is selected from the lesson owner's model configuration, not worker-thread security state.
     */
    default boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return supportsVisualEvidence();
    }

    /**
     * Provider accounts can impose a lower safe request concurrency than the lesson executor.
     */
    default int maxConcurrentSectionRequests(String modelConfigurationOwner) {
        return Integer.MAX_VALUE;
    }

    /**
     * Estimates the complete provider input for one composition attempt.
     *
     * <p>The default keeps test and local adapters lightweight. Provider adapters should override this boundary when
     * they add system prompts, output schemas, or other request material that the application cannot see.</p>
     */
    default InputTokenProfile compositionInputProfile(SectionRequest request) {
        return approximateInputProfile(request, "", providerId());
    }

    /** Estimates a separately budgeted malformed-output repair request. */
    default InputTokenProfile compositionRepairInputProfile(SectionRequest request) {
        return compositionInputProfile(request);
    }

    /** Estimates the complete provider input for one application-requested revision. */
    default InputTokenProfile revisionInputProfile(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        return approximateInputProfile(request, previousDraft + " " + feedback, providerId());
    }

    /** Estimates a separately budgeted malformed revision-output repair request. */
    default InputTokenProfile revisionRepairInputProfile(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        return revisionInputProfile(request, previousDraft, feedback);
    }

    /** Estimates the provider's structured response representation rather than internal UUID-bearing values. */
    default int estimatedOutputTokens(SectionRequest request, SectionDraft draft) {
        return estimateTokens(draft.toString());
    }

    default ModelInvocation composeInvocation(SectionRequest request) {
        SectionDraft draft = compose(request);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation composeInvocation(
            SectionRequest request,
            CaptureHandle capture,
            TraceEventContext context,
            int attempt) {
        return composeInvocation(request);
    }

    default ModelInvocation repairCompositionContractInvocation(SectionRequest request) {
        SectionDraft draft = repairCompositionContract(request);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation repairCompositionContractInvocation(
            SectionRequest request,
            CaptureHandle capture,
            TraceEventContext context,
            int attempt) {
        return repairCompositionContractInvocation(request);
    }

    default ModelInvocation reviseInvocation(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        SectionDraft draft = revise(request, previousDraft, feedback);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation reviseInvocation(
            SectionRequest request,
            SectionDraft previousDraft,
            List<String> feedback,
            CaptureHandle capture,
            TraceEventContext context,
            int attempt) {
        return reviseInvocation(request, previousDraft, feedback);
    }

    default ModelInvocation repairRevisionContractInvocation(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        SectionDraft draft = repairRevisionContract(request, previousDraft, feedback);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation repairRevisionContractInvocation(
            SectionRequest request,
            SectionDraft previousDraft,
            List<String> feedback,
            CaptureHandle capture,
            TraceEventContext context,
            int attempt) {
        return repairRevisionContractInvocation(request, previousDraft, feedback);
    }

    SectionDraft compose(SectionRequest request);

    /**
     * Performs one explicit provider attempt to repair a malformed composition response.
     *
     * <p>The application calls this separately so the repair consumes its own model budget and audit activity.</p>
     */
    default SectionDraft repairCompositionContract(SectionRequest request) {
        return compose(request);
    }

    default SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        return compose(request);
    }

    /** Performs one explicit provider attempt to repair a malformed revision response. */
    default SectionDraft repairRevisionContract(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        return revise(request, previousDraft, feedback);
    }

    /** Identifies an untrusted provider response that could not satisfy the section output contract. */
    final class InvalidOutputException extends RuntimeException {

        public InvalidOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Content-free request-size attribution persisted with the matching audited model activity.
     *
     * <p>Components are estimates rather than provider billing values. They deliberately contain only bounded route
     * and size metadata, never prompt, rulebook, user, or model-response content.</p>
     */
    record InputTokenProfile(
            String providerId,
            int totalTokens,
            int fixedContractTokens,
            int objectiveTokens,
            int requiredRuleTokens,
            int evidenceTokens,
            int chapterScopeTokens,
            int continuityTokens,
            int revisionTokens,
            int otherRequestTokens) {

        public InputTokenProfile {
            if (providerId == null || providerId.isBlank() || providerId.length() > 40
                    || totalTokens < 1
                    || fixedContractTokens < 0
                    || objectiveTokens < 0
                    || requiredRuleTokens < 0
                    || evidenceTokens < 0
                    || chapterScopeTokens < 0
                    || continuityTokens < 0
                    || revisionTokens < 0
                    || otherRequestTokens < 0) {
                throw new IllegalArgumentException("teaching input token profile is invalid");
            }
            long componentTotal = (long) fixedContractTokens
                    + objectiveTokens
                    + requiredRuleTokens
                    + evidenceTokens
                    + chapterScopeTokens
                    + continuityTokens
                    + revisionTokens
                    + otherRequestTokens;
            if (componentTotal != totalTokens) {
                throw new IllegalArgumentException("teaching input token profile total is inconsistent");
            }
            providerId = providerId.strip();
        }
    }

    /** Provider usage returned with one section attempt; zero means that the adapter did not expose that field. */
    record ModelInvocation(
            SectionDraft draft,
            int promptTokens,
            int completionTokens,
            long cacheReadInputTokens) {

        public ModelInvocation {
            if (draft == null || promptTokens < 0 || completionTokens < 0 || cacheReadInputTokens < 0) {
                throw new IllegalArgumentException("teaching model invocation metadata is invalid");
            }
        }
    }

    record SectionRequest(
            String topicKey,
            String title,
            String objective,
            List<String> coverageTags,
            List<PriorSectionContext> priorSections,
            List<EvidenceInput> evidence,
            List<PageImageInput> pageImages,
            List<String> requiredRuleIntents,
            List<TeachingUnitInput> teachingUnits,
            String modelConfigurationOwner,
            String chapterScope,
            WholeGameContextInput wholeGameContext) {

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence,
                List<PageImageInput> pageImages,
                List<String> requiredRuleIntents,
                List<TeachingUnitInput> teachingUnits,
                String modelConfigurationOwner,
                String chapterScope) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    priorSections,
                    evidence,
                    pageImages,
                    requiredRuleIntents,
                    teachingUnits,
                    modelConfigurationOwner,
                    chapterScope,
                    WholeGameContextInput.unavailable());
        }

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    priorSections,
                    evidence,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    "");
        }

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence,
                List<PageImageInput> pageImages) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    priorSections,
                    evidence,
                    pageImages,
                    List.of(),
                    List.of(),
                    null,
                    "");
        }

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence,
                List<PageImageInput> pageImages,
                List<String> requiredRuleIntents,
                String modelConfigurationOwner,
                String chapterScope) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    priorSections,
                    evidence,
                    pageImages,
                    requiredRuleIntents,
                    List.of(),
                    modelConfigurationOwner,
                    chapterScope);
        }

        public SectionRequest {
            if (topicKey == null || topicKey.isBlank()
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || coverageTags == null
                    || priorSections == null || priorSections.size() > 2
                    || evidence == null || evidence.isEmpty()
                    || pageImages == null || pageImages.size() > 2
                    || requiredRuleIntents == null
                    || requiredRuleIntents.stream()
                            .anyMatch(intent -> intent == null || intent.isBlank() || intent.length() > 300)
                    || teachingUnits == null
                    || teachingUnits.stream().anyMatch(java.util.Objects::isNull)
                    || chapterScope == null
                    || wholeGameContext == null) {
                throw new IllegalArgumentException("teaching model request is invalid");
            }
            coverageTags = List.copyOf(coverageTags);
            priorSections = List.copyOf(priorSections);
            evidence = List.copyOf(evidence);
            pageImages = List.copyOf(pageImages);
            requiredRuleIntents = requiredRuleIntents.stream().map(String::strip).distinct().toList();
            teachingUnits = List.copyOf(teachingUnits);
            chapterScope = chapterScope.strip();
        }
    }

    /** One grouping decision made by the outline Agent, not a fixed lesson-template category. */
    record TeachingUnitInput(
            String unitId,
            List<String> sourceIdentifiers,
            List<UUID> directEvidenceIds,
            List<SourceCoverageRole> roles,
            SourceCoverageAvailability availability) {

        public TeachingUnitInput(
                String unitId, List<String> sourceIdentifiers, List<UUID> directEvidenceIds) {
            this(unitId, sourceIdentifiers, directEvidenceIds, List.of(), null);
        }

        public TeachingUnitInput(String unitId, List<String> sourceIdentifiers) {
            this(unitId, sourceIdentifiers, List.of(), List.of(), null);
        }

        public TeachingUnitInput {
            if (unitId == null || unitId.isBlank()
                    || sourceIdentifiers == null || sourceIdentifiers.isEmpty()
                    || sourceIdentifiers.stream().anyMatch(identifier -> identifier == null
                            || identifier.isBlank())
                    || directEvidenceIds == null
                    || directEvidenceIds.stream().anyMatch(java.util.Objects::isNull)
                    || roles == null
                    || roles.stream().anyMatch(java.util.Objects::isNull)
                    || ((roles.isEmpty()) != (availability == null))
                    || (availability == SourceCoverageAvailability.SOURCED && directEvidenceIds.isEmpty())
                    || (availability == SourceCoverageAvailability.UNRESOLVED && !directEvidenceIds.isEmpty())) {
                throw new IllegalArgumentException("teaching unit input is invalid");
            }
            unitId = unitId.strip();
            sourceIdentifiers = sourceIdentifiers.stream().map(String::strip).distinct().toList();
            directEvidenceIds = directEvidenceIds.stream().distinct().toList();
            roles = roles.stream().distinct().toList();
        }

        public boolean typed() {
            return availability != null;
        }
    }

    /** Same immutable source-bound orientation is sent to every independently generated chapter. */
    record WholeGameContextInput(
            String summary,
            List<GlobalConceptInput> concepts,
            List<TopicDependencyInput> topicDependencies,
            boolean evidenceBound) {
        public WholeGameContextInput {
            if (summary == null || summary.isBlank()
                    || concepts == null || concepts.stream().anyMatch(java.util.Objects::isNull)
                    || topicDependencies == null
                    || topicDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || (evidenceBound && concepts.isEmpty())) {
                throw new IllegalArgumentException("whole-game model input is invalid");
            }
            summary = summary.strip();
            concepts = List.copyOf(concepts);
            topicDependencies = List.copyOf(topicDependencies);
        }

        public static WholeGameContextInput unavailable() {
            return new WholeGameContextInput(
                    "No source-bound whole-game context is available for this legacy request.",
                    List.of(),
                    List.of(),
                    false);
        }
    }

    record GlobalConceptInput(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceIdentifiers,
            List<Integer> sourcePageNumbers,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {
        public GlobalConceptInput {
            if (conceptId == null || conceptId.isBlank() || label == null || label.isBlank()
                    || explanation == null || explanation.isBlank()
                    || sourceIdentifiers == null || sourceIdentifiers.isEmpty()
                    || sourcePageNumbers == null || sourcePageNumbers.isEmpty()
                    || relatedTopicKeys == null || relatedTopicKeys.isEmpty()
                    || prerequisiteConceptIds == null) {
                throw new IllegalArgumentException("whole-game concept input is invalid");
            }
            sourceIdentifiers = List.copyOf(sourceIdentifiers);
            sourcePageNumbers = List.copyOf(sourcePageNumbers);
            relatedTopicKeys = List.copyOf(relatedTopicKeys);
            prerequisiteConceptIds = List.copyOf(prerequisiteConceptIds);
        }
    }

    record TopicDependencyInput(String prerequisiteTopicKey, String dependentTopicKey, String reason) {
        public TopicDependencyInput {
            if (prerequisiteTopicKey == null || prerequisiteTopicKey.isBlank()
                    || dependentTopicKey == null || dependentTopicKey.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("whole-game dependency input is invalid");
            }
        }
    }

    record PriorSectionContext(String topicKey, String title, String closingStep) {
        public PriorSectionContext {
            if (topicKey == null || topicKey.isBlank() || title == null || title.isBlank()
                    || closingStep == null || closingStep.isBlank()) {
                throw new IllegalArgumentException("prior teaching section context is invalid");
            }
        }
    }

    record EvidenceInput(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            String visualPresentation,
            EvidenceContentKind contentKind,
            int pageFrom,
            int pageTo) {

        public EvidenceInput(
                UUID chunkId,
                String sectionType,
                String heading,
                String excerpt,
                int pageFrom,
                int pageTo) {
            this(
                    chunkId,
                    sectionType,
                    heading,
                    excerpt,
                    null,
                    EvidenceContentKind.CANONICAL_TEXT,
                    pageFrom,
                    pageTo);
        }

        public EvidenceInput {
            if (chunkId == null || sectionType == null || sectionType.isBlank()
                    || heading == null || heading.isBlank() || excerpt == null || excerpt.isBlank()
                    || contentKind == null || pageFrom < 1 || pageTo < pageFrom) {
                throw new IllegalArgumentException("teaching evidence input is invalid");
            }
            visualPresentation = visualPresentation == null || visualPresentation.isBlank()
                    ? null
                    : visualPresentation.strip();
        }
    }

    enum EvidenceContentKind {
        CANONICAL_TEXT,
        VISUAL_PLACEHOLDER,
        CANONICAL_TEXT_WITH_VISUAL_FACTS,
        VISUAL_TRANSCRIPTION
    }

    record PageImageInput(int pageNumber, String mediaType, byte[] content, int width, int height) {
        public PageImageInput {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0
                    || width < 1 || height < 1) {
                throw new IllegalArgumentException("teaching page image is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record SectionDraft(
            String title,
            VisualKind visualKind,
            String visualCaption,
            List<UUID> visualCitationIds,
            List<StepDraft> steps) {
        public SectionDraft {
            visualCitationIds = visualCitationIds == null ? List.of() : List.copyOf(visualCitationIds);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record StepDraft(
            String heading,
            TeachingMove kind,
            String text,
            List<UUID> citationIds,
            List<String> teachingUnitIds,
            List<RuleFactDraft> ruleFacts,
            VisualFocusDraft visualFocus) {
        public StepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            if (teachingUnitIds != null && teachingUnitIds.stream()
                    .anyMatch(unitId -> unitId == null || unitId.isBlank())) {
                throw new IllegalArgumentException("teaching step unit references are invalid");
            }
            teachingUnitIds = teachingUnitIds == null ? List.of() : List.copyOf(teachingUnitIds);
            ruleFacts = ruleFacts == null ? List.of() : List.copyOf(ruleFacts);
        }

        public StepDraft(
                String heading,
                TeachingMove kind,
                String text,
                List<UUID> citationIds,
                VisualFocusDraft visualFocus) {
            this(heading, kind, text, citationIds, List.of(), List.of(), visualFocus);
        }

        public StepDraft(
                String heading,
                TeachingMove kind,
                String text,
                List<UUID> citationIds,
                List<String> teachingUnitIds,
                VisualFocusDraft visualFocus) {
            this(heading, kind, text, citationIds, teachingUnitIds, List.of(), visualFocus);
        }

        public StepDraft(String heading, TeachingMove kind, String text, List<UUID> citationIds) {
            this(heading, kind, text, citationIds, List.of(), List.of(), null);
        }

        public StepDraft(String text, List<UUID> citationIds) {
            this("照着做", TeachingMove.DO, text, citationIds, List.of(), List.of(), null);
        }
    }

    /** One atomic, independently cited fact used for reader layout; it never replaces the natural step text. */
    record RuleFactDraft(RuleFactRole role, String text, List<UUID> citationIds) {
        public RuleFactDraft {
            if (role == null || text == null || text.isBlank() || citationIds == null || citationIds.isEmpty()) {
                throw new IllegalArgumentException("teaching rule fact draft is invalid");
            }
            text = text.strip();
            citationIds = List.copyOf(citationIds);
        }
    }

    record VisualFocusDraft(
            int pageNumber,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height) {
        public VisualFocusDraft {
            label = label == null ? "" : label;
            visibleDescription = visibleDescription == null ? "" : visibleDescription;
        }

        public VisualFocusDraft(
                int pageNumber,
                String label,
                int x,
                int y,
                int width,
                int height) {
            this(pageNumber, label, "", x, y, width, height);
        }
    }

    private static InputTokenProfile approximateInputProfile(
            SectionRequest request, String revision, String providerId) {
        int objectiveTokens = estimateTokens(request.objective());
        int requiredRuleTokens = request.requiredRuleIntents().isEmpty()
                ? 0
                : estimateTokens(request.requiredRuleIntents().toString())
                        + estimateTokens(request.teachingUnits().toString());
        int evidenceTokens = estimateTokens(request.evidence().toString());
        int chapterScopeTokens = estimateTokens(request.chapterScope());
        int continuityTokens = request.priorSections().isEmpty()
                ? 0
                : estimateTokens(request.priorSections().toString());
        int revisionTokens = estimateTokens(revision);
        int otherRequestTokens = estimateTokens(
                request.title() + " " + request.coverageTags() + " " + request.pageImages().size()
                        + " " + request.wholeGameContext());
        int totalTokens = objectiveTokens
                + requiredRuleTokens
                + evidenceTokens
                + chapterScopeTokens
                + continuityTokens
                + revisionTokens
                + otherRequestTokens;
        return new InputTokenProfile(
                providerId,
                totalTokens,
                0,
                objectiveTokens,
                requiredRuleTokens,
                evidenceTokens,
                chapterScopeTokens,
                continuityTokens,
                revisionTokens,
                otherRequestTokens);
    }

    private static int estimateTokens(String value) {
        return value == null || value.isEmpty() ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private ModelInvocation estimatedInvocation(SectionRequest request, SectionDraft draft) {
        return new ModelInvocation(draft, 0, 0, 0);
    }
}
