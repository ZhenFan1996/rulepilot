package com.rulepilot.teaching;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Lets the model decide how this particular game should be taught before retrieval begins. */
public interface TeachingOutlineModel {

    OutlineDraft organize(OutlineRequest request);

    /**
     * Gives an adapter an audited budget reservation for each real provider call in a compound planning graph.
     * Simple implementations remain one-call models through this default boundary.
     */
    default OutlineDraft organize(OutlineRequest request, ModelCallExecutor calls) {
        return calls.invoke(
                new ModelCall(
                        "organizeTeachingOutline",
                        estimateTokens(request),
                        "Rulebook lesson topics organized"),
                () -> organize(request),
                TeachingOutlineModel::estimateTokens);
    }

    /**
     * Rebuilds an otherwise usable outline when its broad flow chapter steals detail owned by later chapters.
     * Implementations must preserve the supplied draft when refinement cannot complete.
     */
    default OutlineDraft refineChapterOwnership(OutlineRequest request, OutlineDraft current, String feedback) {
        return current;
    }

    default OutlineDraft refineChapterOwnership(
            OutlineRequest request,
            OutlineDraft current,
            String feedback,
            ModelCallExecutor calls) {
        return calls.invoke(
                new ModelCall(
                        "refineTeachingOutlineOwnership",
                        estimateTokens(current) + estimateTokens(feedback),
                        "Lesson chapters separated so each detailed rule has one home"),
                () -> refineChapterOwnership(request, current, feedback),
                TeachingOutlineModel::estimateTokens);
    }

    interface ModelCallExecutor {
        <T> T invoke(ModelCall call, Supplier<T> invocation, ToIntFunction<T> outputTokens);

        static ModelCallExecutor direct() {
            return new ModelCallExecutor() {
                @Override
                public <T> T invoke(
                        ModelCall call,
                        Supplier<T> invocation,
                        ToIntFunction<T> outputTokens) {
                    return invocation.get();
                }
            };
        }
    }

    record ModelCall(String operation, int estimatedInputTokens, String successSummary) {
        public ModelCall {
            if (operation == null || operation.isBlank()
                    || estimatedInputTokens < 1
                    || successSummary == null || successSummary.isBlank()) {
                throw new IllegalArgumentException("teaching outline model call is invalid");
            }
        }
    }

    private static int estimateTokens(Object value) {
        return Math.max(1, value == null ? 1 : (value.toString().length() + 3) / 4);
    }

    /**
     * Marks a provider or structured-output failure at the model adapter boundary. Application services may recover
     * from this failure only when an independently complete source ledger is already available.
     */
    final class OutlineGenerationException extends RuntimeException {

        public OutlineGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The immutable source ledger cannot fit the bounded hierarchical planning contract. This is known before any
     * provider call and therefore must not be treated as a transient model or structured-output failure.
     */
    final class OutlineCapacityExceededException extends RuntimeException {

        public OutlineCapacityExceededException(String message) {
            super(message);
        }
    }

    record OutlineRequest(
            List<PageInput> pages,
            List<PageImageInput> pageImages,
            String learningGoal,
            String modelConfigurationOwner) {
        public OutlineRequest {
            learningGoal = normalizeOptional(learningGoal);
            if (pages == null || pages.isEmpty() || pageImages == null) {
                throw new IllegalArgumentException("teaching outline request is invalid");
            }
            pages = List.copyOf(pages);
            pageImages = List.copyOf(pageImages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }

        public String learningGoalForPrompt() {
            return learningGoal == null ? "NO_ADDITIONAL_GOAL" : learningGoal;
        }

        private static String normalizeOptional(String value) {
            if (value == null || value.isBlank()) return null;
            return value.strip();
        }

        public OutlineRequest(
                List<PageInput> pages,
                List<PageImageInput> pageImages,
                String modelConfigurationOwner) {
            this(pages, pageImages, null, modelConfigurationOwner);
        }

        public OutlineRequest(
                List<PageInput> pages,
                List<PageImageInput> pageImages) {
            this(pages, pageImages, null, null);
        }

        public OutlineRequest(List<PageInput> pages) {
            this(pages, List.of(), null, null);
        }
    }

    record PageInput(
            int pageNumber,
            String text,
            List<VisualRulebookPageCatalogModel.SourceDependency> sourceDependencies,
            List<String> sourceRuleGroupIdentifiers,
            boolean sourceRuleGroupInventoryComplete,
            List<VisualRulebookPageCatalogModel.RuleGroupFact> sourceRuleGroupFacts,
            PageLedgerState pageLedgerState) {
        public PageInput {
            if (pageNumber < 1 || text == null || text.isBlank() || sourceDependencies == null
                    || sourceRuleGroupIdentifiers == null
                    || sourceRuleGroupIdentifiers.stream()
                            .anyMatch(identifier -> identifier == null || identifier.isBlank())
                    || sourceRuleGroupFacts == null
                    || sourceRuleGroupFacts.stream().anyMatch(java.util.Objects::isNull)
                    || pageLedgerState == null) {
                throw new IllegalArgumentException("rulebook page input is invalid");
            }
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            sourceRuleGroupIdentifiers = sourceRuleGroupIdentifiers.stream().map(String::strip).distinct().toList();
            sourceRuleGroupFacts = sourceRuleGroupFacts.stream().distinct().toList();
            if (sourceRuleGroupInventoryComplete
                    && !VisualSourceRuleGroupLedger.hasExactFactBindings(
                            sourceRuleGroupIdentifiers, sourceRuleGroupFacts)) {
                throw new IllegalArgumentException("complete page input requires typed rule-group facts");
            }
            switch (pageLedgerState) {
                case VISUAL_EXACT_COMPLETE -> {
                    if (!sourceRuleGroupInventoryComplete) {
                        throw new IllegalArgumentException("exact visual page ledger must be complete");
                    }
                }
                case VISUAL_PARTIAL -> {
                    if (sourceRuleGroupInventoryComplete) {
                        throw new IllegalArgumentException("partial visual page ledger cannot be complete");
                    }
                    if (!VisualSourceRuleGroupLedger.hasExactFactBindings(
                            sourceRuleGroupIdentifiers, sourceRuleGroupFacts)) {
                        throw new IllegalArgumentException(
                                "partial visual page ledger requires exact typed rule-group facts");
                    }
                }
                case VISUAL_EXPLICITLY_UNAVAILABLE -> {
                    if (sourceRuleGroupInventoryComplete
                            || !sourceDependencies.isEmpty()
                            || !sourceRuleGroupIdentifiers.isEmpty()
                            || !sourceRuleGroupFacts.isEmpty()) {
                        throw new IllegalArgumentException("unavailable rulebook page cannot carry source claims");
                    }
                }
                case LEGACY_TEXT -> {
                    // Legacy text inputs intentionally retain their existing source-contract behavior.
                }
            }
        }

        public PageInput(
                int pageNumber,
                String text,
                List<VisualRulebookPageCatalogModel.SourceDependency> sourceDependencies,
                List<String> sourceRuleGroupIdentifiers,
                boolean sourceRuleGroupInventoryComplete,
                List<VisualRulebookPageCatalogModel.RuleGroupFact> sourceRuleGroupFacts) {
            this(
                    pageNumber,
                    text,
                    sourceDependencies,
                    sourceRuleGroupIdentifiers,
                    sourceRuleGroupInventoryComplete,
                    sourceRuleGroupFacts,
                    PageLedgerState.LEGACY_TEXT);
        }

        public PageInput(
                int pageNumber,
                String text,
                List<VisualRulebookPageCatalogModel.SourceDependency> sourceDependencies,
                List<String> sourceRuleGroupIdentifiers,
                boolean sourceRuleGroupInventoryComplete) {
            this(
                    pageNumber,
                    text,
                    sourceDependencies,
                    sourceRuleGroupIdentifiers,
                    sourceRuleGroupInventoryComplete,
                    List.of(),
                    PageLedgerState.LEGACY_TEXT);
        }

        public PageInput(
                int pageNumber,
                String text,
                List<VisualRulebookPageCatalogModel.SourceDependency> sourceDependencies) {
            this(
                    pageNumber,
                    text,
                    sourceDependencies,
                    List.of(),
                    false,
                    List.of(),
                    PageLedgerState.LEGACY_TEXT);
        }

        public PageInput(int pageNumber, String text) {
            this(
                    pageNumber,
                    text,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    PageLedgerState.LEGACY_TEXT);
        }
    }

    /** Typed provenance for the page ledger; planning must never infer this state from prompt prose. */
    enum PageLedgerState {
        LEGACY_TEXT,
        VISUAL_EXACT_COMPLETE,
        VISUAL_PARTIAL,
        VISUAL_EXPLICITLY_UNAVAILABLE
    }

    record PageImageInput(int pageNumber, String mediaType, byte[] content) {
        public PageImageInput {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0) {
                throw new IllegalArgumentException("rulebook outline page image is invalid");
            }
            mediaType = mediaType.strip();
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record OutlineDraft(
            String gameTitle,
            String premise,
            List<TopicDraft> topics,
            List<SourceCoverageSlotDraft> sourceCoverageSlots,
            boolean sourceCoverageInventoryComplete,
            WholeGameUnderstandingDraft wholeGameUnderstanding) {

        public OutlineDraft {
            if (gameTitle == null || gameTitle.isBlank()
                    || premise == null || premise.isBlank()) {
                throw new IllegalArgumentException("teaching outline identity is invalid");
            }
            if (sourceCoverageSlots != null && sourceCoverageSlots.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("teaching source coverage inventory is invalid");
            }
            topics = topics == null ? List.of() : List.copyOf(topics);
            sourceCoverageSlots = sourceCoverageSlots == null ? List.of() : List.copyOf(sourceCoverageSlots);
            wholeGameUnderstanding = wholeGameUnderstanding == null
                    ? new WholeGameUnderstandingDraft(premise, List.of(), List.of())
                    : wholeGameUnderstanding;
        }

        public OutlineDraft(
                String gameTitle,
                String premise,
                List<TopicDraft> topics,
                List<SourceCoverageSlotDraft> sourceCoverageSlots,
                boolean sourceCoverageInventoryComplete) {
            this(gameTitle, premise, topics, sourceCoverageSlots, sourceCoverageInventoryComplete, null);
        }

        public OutlineDraft(String gameTitle, String premise, List<TopicDraft> topics) {
            this(gameTitle, premise, topics, List.of(), false);
        }
    }

    /** The shared, source-bound mental model that must exist before chapter generation can fan out. */
    record WholeGameUnderstandingDraft(
            String summary,
            List<GlobalConceptDraft> concepts,
            List<TopicDependencyDraft> topicDependencies) {
        public WholeGameUnderstandingDraft {
            if (summary == null || summary.isBlank()
                    || concepts == null || concepts.stream().anyMatch(java.util.Objects::isNull)
                    || topicDependencies == null
                    || topicDependencies.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("whole-game teaching understanding is invalid");
            }
            concepts = List.copyOf(concepts);
            topicDependencies = List.copyOf(topicDependencies);
        }
    }

    /** One Agent-chosen global concept; labels and dimensions come from the active rulebook, not a fixed checklist. */
    record GlobalConceptDraft(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceIdentifiers,
            List<Integer> sourcePageNumbers,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {
        public GlobalConceptDraft {
            if (conceptId == null || conceptId.isBlank()
                    || label == null || label.isBlank()
                    || explanation == null || explanation.isBlank()
                    || sourceIdentifiers == null || sourceIdentifiers.isEmpty()
                    || sourceIdentifiers.stream().anyMatch(identifier -> identifier == null
                            || identifier.isBlank())
                    || sourcePageNumbers == null || sourcePageNumbers.isEmpty()
                    || sourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)
                    || relatedTopicKeys == null
                    || relatedTopicKeys.stream().anyMatch(topic -> topic == null || topic.isBlank())
                    || prerequisiteConceptIds == null
                    || prerequisiteConceptIds.stream().anyMatch(concept -> concept == null
                            || concept.isBlank())) {
                throw new IllegalArgumentException("whole-game teaching concept is invalid");
            }
            sourceIdentifiers = sourceIdentifiers.stream().distinct().toList();
            sourcePageNumbers = sourcePageNumbers.stream().distinct().toList();
            relatedTopicKeys = relatedTopicKeys.stream().distinct().toList();
            prerequisiteConceptIds = prerequisiteConceptIds.stream().distinct().toList();
        }
    }

    /** A pedagogical ordering decision made after the Agent has understood the whole active rulebook. */
    record TopicDependencyDraft(String prerequisiteTopicKey, String dependentTopicKey, String reason) {
        public TopicDependencyDraft {
            if (prerequisiteTopicKey == null || prerequisiteTopicKey.isBlank()
                    || dependentTopicKey == null || dependentTopicKey.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("whole-game topic dependency is invalid");
            }
        }
    }

    /** One independently auditable obligation from the active rulebook, before chapter prose is generated. */
    record SourceCoverageSlotDraft(
            String slotId,
            SourceCoverageRole role,
            String sourceIdentifier,
            List<Integer> sourcePageNumbers,
            String ownerTopicKey,
            String teachingUnitId,
            SourceCoverageAvailability availability) {
        public SourceCoverageSlotDraft {
            teachingUnitId = teachingUnitId == null || teachingUnitId.isBlank() ? slotId : teachingUnitId;
            if (slotId == null || slotId.isBlank())
                throw new IllegalArgumentException("teaching source slotId is invalid");
            if (role == null) throw new IllegalArgumentException("teaching source slot role is missing");
            if (sourceIdentifier == null || sourceIdentifier.isBlank()
                    || sourceIdentifier.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("teaching source slot identifier is invalid");
            if (sourcePageNumbers == null
                    || sourcePageNumbers.stream().anyMatch(page -> page == null || page < 1))
                throw new IllegalArgumentException("teaching source slot pages are invalid");
            if (ownerTopicKey == null || ownerTopicKey.isBlank())
                throw new IllegalArgumentException("teaching source slot owner is invalid");
            if (teachingUnitId == null || teachingUnitId.isBlank())
                throw new IllegalArgumentException("teaching source slot teachingUnitId is invalid");
            if (availability == null)
                throw new IllegalArgumentException("teaching source slot availability is missing");
            sourcePageNumbers = sourcePageNumbers.stream().distinct().toList();
            if (availability != SourceCoverageAvailability.UNRESOLVED && sourcePageNumbers.isEmpty()) {
                throw new IllegalArgumentException("sourced teaching coverage slots require a source page");
            }
        }

        /**
         * Compatibility constructor for source ledgers created before teaching units were explicit. Each old slot is
         * treated as one independently planned unit; new model output may deliberately group closely coupled slots by
         * returning the same {@code teachingUnitId}.
         */
        public SourceCoverageSlotDraft(
                String slotId,
                SourceCoverageRole role,
                String sourceIdentifier,
                List<Integer> sourcePageNumbers,
                String ownerTopicKey,
                SourceCoverageAvailability availability) {
            this(slotId, role, sourceIdentifier, sourcePageNumbers, ownerTopicKey, slotId, availability);
        }
    }

    public enum SourceCoverageRole {
        SETUP,
        CORE_LOOP,
        LEGAL_ACTION,
        ENDING,
        SCORING,
        NECESSARY_EXCEPTION,
        SUPPORTING_RULE
    }

    public enum SourceCoverageAvailability {
        SOURCED,
        MISSING_EXTERNAL_SOURCE,
        UNRESOLVED
    }

    record TopicDraft(
            String key,
            String title,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<String> retrievalQueries,
            List<String> coverageTags,
            List<Integer> sourcePageNumbers) {
        public TopicDraft {
            if (key == null || key.isBlank()
                    || !key.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || (retrievalQueries != null && retrievalQueries.stream()
                            .anyMatch(query -> query == null || query.isBlank()))
                    || (sourcePageNumbers != null && sourcePageNumbers.stream()
                            .anyMatch(pageNumber -> pageNumber == null || pageNumber < 1))) {
                throw new IllegalArgumentException("teaching outline topic is invalid");
            }
            retrievalQueries = retrievalQueries == null ? List.of() : List.copyOf(retrievalQueries);
            coverageTags = coverageTags == null ? List.of() : List.copyOf(coverageTags);
            sourcePageNumbers = sourcePageNumbers == null ? List.of() : sourcePageNumbers.stream().distinct().toList();
        }

        public TopicDraft(
                String key,
                String title,
                String objective,
                boolean required,
                boolean visualEvidenceRecommended,
                List<String> retrievalQueries,
                List<String> coverageTags) {
            this(key, title, objective, required, visualEvidenceRecommended, retrievalQueries, coverageTags, List.of());
        }
    }
}
