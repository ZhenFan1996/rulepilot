package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
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

    default ModelInvocation repairCompositionContractInvocation(SectionRequest request) {
        SectionDraft draft = repairCompositionContract(request);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation reviseInvocation(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        SectionDraft draft = revise(request, previousDraft, feedback);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation repairRevisionContractInvocation(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        SectionDraft draft = repairRevisionContract(request, previousDraft, feedback);
        return estimatedInvocation(request, draft);
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
            String modelConfigurationOwner,
            String chapterScope) {

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
                    null,
                    "");
        }

        public SectionRequest {
            if (topicKey == null || topicKey.isBlank()
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || coverageTags == null
                    || priorSections == null || priorSections.size() > 2
                    || evidence == null || evidence.isEmpty()
                    || pageImages == null || pageImages.size() > 2
                    || requiredRuleIntents == null || requiredRuleIntents.size() > 8
                    || requiredRuleIntents.stream()
                            .anyMatch(intent -> intent == null || intent.isBlank() || intent.length() > 300)
                    || chapterScope == null || chapterScope.length() > 4_000) {
                throw new IllegalArgumentException("teaching model request is invalid");
            }
            coverageTags = List.copyOf(coverageTags);
            priorSections = List.copyOf(priorSections);
            evidence = List.copyOf(evidence);
            pageImages = List.copyOf(pageImages);
            requiredRuleIntents = requiredRuleIntents.stream().map(String::strip).distinct().toList();
            chapterScope = chapterScope.strip();
        }
    }

    record PriorSectionContext(String topicKey, String title, String closingStep) {
        public PriorSectionContext {
            if (topicKey == null || topicKey.isBlank() || title == null || title.isBlank() || title.length() > 160
                    || closingStep == null || closingStep.isBlank() || closingStep.length() > 600) {
                throw new IllegalArgumentException("prior teaching section context is invalid");
            }
            title = title.strip();
            closingStep = closingStep.strip();
        }
    }

    record EvidenceInput(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}

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
            VisualFocusDraft visualFocus) {
        public StepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }

        public StepDraft(String heading, TeachingMove kind, String text, List<UUID> citationIds) {
            this(heading, kind, text, citationIds, null);
        }

        public StepDraft(String text, List<UUID> citationIds) {
            this("照着做", TeachingMove.DO, text, citationIds, null);
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
            label = label == null ? "" : label.strip();
            visibleDescription = visibleDescription == null ? "" : visibleDescription.strip();
            if (visibleDescription.length() > 240) {
                throw new IllegalArgumentException("visual focus description is too long");
            }
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
                : estimateTokens(request.requiredRuleIntents().toString());
        int evidenceTokens = estimateTokens(request.evidence().toString());
        int chapterScopeTokens = estimateTokens(request.chapterScope());
        int continuityTokens = request.priorSections().isEmpty()
                ? 0
                : estimateTokens(request.priorSections().toString());
        int revisionTokens = estimateTokens(revision);
        int otherRequestTokens = estimateTokens(
                request.title() + " " + request.coverageTags() + " " + request.pageImages().size());
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
