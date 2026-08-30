package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface TeachingLessonModel {

    default String providerId() {
        return "unspecified";
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

    /** Estimates the next turn after the same section Agent receives a rejected candidate observation. */
    default InputTokenProfile continuationInputProfile(
            SectionRequest request, CandidateRejection rejection) {
        return approximateInputProfile(request, rejection.toString(), providerId());
    }

    /** Estimates the provider's structured response representation rather than internal UUID-bearing values. */
    default int estimatedOutputTokens(SectionRequest request, SectionDraft draft) {
        return estimateTokens(draft.toString());
    }

    default ModelInvocation composeInvocation(SectionRequest request) {
        SectionDraft draft = compose(request);
        return estimatedInvocation(request, draft);
    }

    default ModelInvocation continueAfterRejectionInvocation(
            SectionRequest request, CandidateRejection rejection) {
        SectionDraft draft = continueAfterRejection(request, rejection);
        return estimatedInvocation(request, draft);
    }

    SectionDraft compose(SectionRequest request);

    /**
     * Continues the same section Agent with one complete rejection observation.
     *
     * <p>The returned value is always a complete replacement candidate. The application never interprets a prose
     * patch or combines fields from multiple candidates.</p>
     */
    default SectionDraft continueAfterRejection(
            SectionRequest request, CandidateRejection rejection) {
        return compose(request);
    }

    /** Builds the complete observation for a candidate that reached deterministic application validation. */
    default CandidateRejection rejectionObservation(
            SectionRequest request,
            SectionDraft candidate,
            String code,
            String path,
            String reason) {
        return rejectionObservation(request, candidate == null ? "" : candidate.toString(), code, path, reason);
    }

    /** Enriches a raw provider candidate with the original contract and every allowed opaque identity. */
    default CandidateRejection rejectionObservation(
            SectionRequest request,
            String candidateJson,
            String code,
            String path,
            String reason) {
        return new CandidateRejection(
                candidateJson,
                code,
                path,
                reason,
                "{\"title\":\"...\",\"steps\":[{\"heading\":\"...\",\"kind\":\"DO\",\"text\":\"...\",\"citationIds\":[\"E1\"]}]}",
                request.topicKey(),
                request.evidence().stream().map(input -> input.chunkId().toString()).toList());
    }

    /** Identifies an untrusted provider response that could not satisfy the section output contract. */
    final class InvalidOutputException extends RuntimeException {

        private final String rejectedCandidate;
        private final String code;
        private final String path;
        private final String reason;

        public InvalidOutputException(String message, Throwable cause) {
            this("INVALID_JSON", "$", message, "", cause);
        }

        public InvalidOutputException(String message, String rejectedCandidate, Throwable cause) {
            this("INVALID_JSON", "$", message, rejectedCandidate, cause);
        }

        public InvalidOutputException(
                String code, String path, String message, String rejectedCandidate, Throwable cause) {
            super(message, cause);
            this.rejectedCandidate = rejectedCandidate == null ? "" : rejectedCandidate;
            this.code = required(code, "teaching rejection code");
            this.path = required(path, "teaching rejection path");
            this.reason = detailedError(message, cause);
        }

        public String rejectedCandidate() {
            return rejectedCandidate;
        }

        public String code() {
            return code;
        }

        public String path() {
            return path;
        }

        public String reason() {
            return reason;
        }

        private static String detailedError(String message, Throwable cause) {
            StringBuilder diagnostic = new StringBuilder(message == null || message.isBlank()
                    ? "teaching model output was rejected"
                    : message.strip());
            Throwable current = cause;
            while (current != null) {
                if (current.getMessage() != null && !current.getMessage().isBlank()) {
                    diagnostic.append("; caused by ")
                            .append(current.getClass().getSimpleName())
                            .append(": ")
                            .append(current.getMessage());
                }
                current = current.getCause();
            }
            return diagnostic.toString();
        }

        private static String required(String value, String label) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
            return value.strip();
        }
    }

    /** Complete, untruncated validation feedback passed back to the same section Agent. */
    record CandidateRejection(
            String candidateJson,
            String code,
            String path,
            String reason,
            String schema,
            String sectionIdentity,
            List<String> allowedEvidenceIdentities) {

        public CandidateRejection {
            candidateJson = candidateJson == null ? "" : candidateJson;
            if (code == null || code.isBlank()
                    || path == null || path.isBlank()
                    || reason == null || reason.isBlank()
                    || schema == null || schema.isBlank()
                    || sectionIdentity == null || sectionIdentity.isBlank()) {
                throw new IllegalArgumentException("teaching candidate rejection is incomplete");
            }
            code = code.strip();
            path = path.strip();
            reason = reason.strip();
            schema = schema.strip();
            sectionIdentity = sectionIdentity.strip();
            allowedEvidenceIdentities = List.copyOf(Objects.requireNonNull(allowedEvidenceIdentities));
        }
    }

    /** Keeps provider transport and availability details behind the Teaching model port. */
    final class ProviderFailureException extends RuntimeException {

        public ProviderFailureException(Throwable cause) {
            super("teaching model provider failed", cause);
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
            int contractTokens,
            int evidenceTokens,
            int continuityTokens,
            int revisionTokens) {

        public InputTokenProfile {
            if (providerId == null || providerId.isBlank() || providerId.length() > 40
                    || totalTokens < 1
                    || contractTokens < 0
                    || evidenceTokens < 0
                    || continuityTokens < 0
                    || revisionTokens < 0) {
                throw new IllegalArgumentException("teaching input token profile is invalid");
            }
            long componentTotal = (long) contractTokens
                    + evidenceTokens
                    + continuityTokens
                    + revisionTokens;
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
            List<PriorSectionContext> priorSections,
            List<EvidenceInput> evidence,
            String modelConfigurationOwner) {

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence) {
            this(
                    topicKey,
                    title,
                    objective,
                    priorSections,
                    evidence,
                    null);
        }

        public SectionRequest {
            if (topicKey == null || topicKey.isBlank()
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || priorSections == null
                    || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("teaching model request is invalid");
            }
            priorSections = List.copyOf(priorSections);
            evidence = List.copyOf(evidence);
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

    record SectionDraft(
            String title,
            List<StepDraft> steps) {
        public SectionDraft {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record StepDraft(
            String heading,
            TeachingMove kind,
            String text,
            List<UUID> citationIds,
            List<RuleFactDraft> ruleFacts) {
        public StepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            ruleFacts = ruleFacts == null ? List.of() : List.copyOf(ruleFacts);
        }

        public StepDraft(
                String heading,
                TeachingMove kind,
                String text,
                List<UUID> citationIds) {
            this(heading, kind, text, citationIds, List.of());
        }

        public StepDraft(String text, List<UUID> citationIds) {
            this("照着做", TeachingMove.DO, text, citationIds, List.of());
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

    private static InputTokenProfile approximateInputProfile(
            SectionRequest request, String revision, String providerId) {
        int contractTokens = estimateTokens(request.title()) + estimateTokens(request.objective());
        int evidenceTokens = estimateTokens(request.evidence().toString());
        int continuityTokens = request.priorSections().isEmpty()
                ? 0
                : estimateTokens(request.priorSections().toString());
        int revisionTokens = estimateTokens(revision);
        int totalTokens = contractTokens
                + evidenceTokens
                + continuityTokens
                + revisionTokens;
        return new InputTokenProfile(
                providerId,
                totalTokens,
                contractTokens,
                evidenceTokens,
                continuityTokens,
                revisionTokens);
    }

    private static int estimateTokens(String value) {
        return value == null || value.isEmpty() ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private ModelInvocation estimatedInvocation(SectionRequest request, SectionDraft draft) {
        return new ModelInvocation(draft, 0, 0, 0);
    }
}
