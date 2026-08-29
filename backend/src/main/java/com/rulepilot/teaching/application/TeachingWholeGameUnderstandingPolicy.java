package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Verifies that a source-bound whole-game model exists before independent chapter composition begins. */
final class TeachingWholeGameUnderstandingPolicy {

    static final String CONTRACT_TAG = "whole_game_context_v1";

    private TeachingWholeGameUnderstandingPolicy() {}

    static void validateComplete(OutlineDraft outline) {
        if (outline == null || outline.wholeGameUnderstanding() == null
                || outline.wholeGameUnderstanding().concepts().isEmpty()) {
            throw new IllegalArgumentException(
                    "teaching outline must form a source-bound whole-game understanding before chapter generation");
        }

        Map<String, Integer> topicPositions = new LinkedHashMap<>();
        for (int index = 0; index < outline.topics().size(); index++) {
            String key = outline.topics().get(index).key();
            if (key == null || key.isBlank() || topicPositions.putIfAbsent(key, index) != null) {
                throw new IllegalArgumentException("whole-game understanding requires unique topic keys");
            }
        }

        Map<String, Integer> conceptPositions = new LinkedHashMap<>();
        for (int index = 0; index < outline.wholeGameUnderstanding().concepts().size(); index++) {
            GlobalConceptDraft concept = outline.wholeGameUnderstanding().concepts().get(index);
            if (conceptPositions.putIfAbsent(concept.conceptId(), index) != null) {
                throw new IllegalArgumentException("whole-game concept IDs must be unique");
            }
        }

        List<String> sourceBindingErrors = new ArrayList<>();
        for (GlobalConceptDraft concept : outline.wholeGameUnderstanding().concepts()) {
            for (String prerequisite : concept.prerequisiteConceptIds()) {
                Integer prerequisitePosition = conceptPositions.get(prerequisite);
                Integer currentPosition = conceptPositions.get(concept.conceptId());
                if (prerequisitePosition == null || prerequisitePosition >= currentPosition) {
                    throw new IllegalArgumentException(
                            "whole-game concept dependencies must point to an earlier known concept");
                }
            }
            for (String topicKey : concept.relatedTopicKeys()) {
                if (!topicPositions.containsKey(topicKey)) {
                    throw new IllegalArgumentException("whole-game concept references an unknown chapter");
                }
            }
            for (String identifier : concept.sourceIdentifiers()) {
                var identifierSlots = outline.sourceCoverageSlots().stream()
                        .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                        .filter(slot -> identity(slot.sourceIdentifier()).equals(identity(identifier)))
                        .toList();
                if (identifierSlots.isEmpty()) {
                    List<String> allowedIdentifiers = outline.sourceCoverageSlots().stream()
                            .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                            .map(slot -> slot.sourceIdentifier())
                            .distinct()
                            .toList();
                    sourceBindingErrors.add("conceptId='" + concept.conceptId() + "', sourceIdentifier='" + identifier
                            + "': sourceIdentifiers[] must exactly match a SOURCED "
                            + "sourceCoverageSlots[].sourceIdentifier; allowedSourcedIdentifiers="
                            + allowedIdentifiers);
                    continue;
                }

                var relatedSlots = identifierSlots.stream()
                        .filter(slot -> concept.relatedTopicKeys().contains(slot.ownerTopicKey()))
                        .toList();
                if (relatedSlots.isEmpty()) {
                    List<String> matchingOwners = identifierSlots.stream()
                            .map(slot -> slot.ownerTopicKey())
                            .distinct()
                            .toList();
                    sourceBindingErrors.add("conceptId='" + concept.conceptId() + "', sourceIdentifier='" + identifier
                            + "': relatedTopicKeys=" + concept.relatedTopicKeys()
                            + " must contain an owner of the matching SOURCED slot; matchingOwnerTopicKeys="
                            + matchingOwners);
                    continue;
                }

                boolean pagesContainCompleteSlot = relatedSlots.stream()
                        .anyMatch(slot -> concept.sourcePageNumbers().containsAll(slot.sourcePageNumbers()));
                if (!pagesContainCompleteSlot) {
                    List<String> matchingRequirements = relatedSlots.stream()
                            .map(slot -> "{ownerTopicKey='" + slot.ownerTopicKey() + "', requiredSourcePageNumbers="
                                    + slot.sourcePageNumbers() + "}")
                            .toList();
                    sourceBindingErrors.add("conceptId='" + concept.conceptId() + "', sourceIdentifier='" + identifier
                            + "': sourcePageNumbers=" + concept.sourcePageNumbers()
                            + " must contain every page from at least one matching SOURCED slot; matchingRequirements="
                            + matchingRequirements);
                }
            }
        }
        if (!sourceBindingErrors.isEmpty()) {
            throw new IllegalArgumentException(
                    "whole-game source binding validation failed:\n- " + String.join("\n- ", sourceBindingErrors));
        }

        Set<String> dependencies = new LinkedHashSet<>();
        for (var dependency : outline.wholeGameUnderstanding().topicDependencies()) {
            Integer prerequisite = topicPositions.get(dependency.prerequisiteTopicKey());
            Integer dependent = topicPositions.get(dependency.dependentTopicKey());
            if (prerequisite == null || dependent == null || prerequisite >= dependent) {
                throw new IllegalArgumentException(
                        "whole-game chapter dependencies must follow the Agent's final teaching order");
            }
            if (!dependencies.add(dependency.prerequisiteTopicKey() + "\n" + dependency.dependentTopicKey())) {
                throw new IllegalArgumentException("whole-game chapter dependencies must be unique");
            }
        }
    }

    static boolean requiresValidatedContext(TeachingPlan plan) {
        return plan.sections().stream()
                .flatMap(section -> section.coverageTags().stream())
                .anyMatch(CONTRACT_TAG::equals);
    }

    static void validateBeforeChapterGeneration(TeachingPlan plan) {
        if (plan == null) throw new IllegalArgumentException("teaching plan is required");
        if (!requiresValidatedContext(plan)) return;
        if (!plan.wholeGameContext().evidenceBound() || plan.wholeGameContext().concepts().isEmpty()) {
            throw new IllegalArgumentException(
                    "source-contracted chapters cannot start before whole-game understanding is complete");
        }
        Map<String, Integer> topicPositions = new LinkedHashMap<>();
        for (int index = 0; index < plan.sections().size(); index++) {
            topicPositions.put(plan.sections().get(index).topicKey(), index);
        }
        for (var concept : plan.wholeGameContext().concepts()) {
            for (String identifier : concept.sourceIdentifiers()) {
                boolean bound = plan.sections().stream()
                        .filter(section -> concept.relatedTopicKeys().contains(section.topicKey()))
                        .filter(section -> section.sourcePageNumbers().stream()
                                .anyMatch(concept.sourcePageNumbers()::contains))
                        .flatMap(section -> TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()).stream())
                        .map(TeachingWholeGameUnderstandingPolicy::identity)
                        .anyMatch(identity(identifier)::equals);
                if (!bound) {
                    throw new IllegalArgumentException("persisted whole-game concept lost its plan-owned source binding");
                }
            }
        }
        for (var dependency : plan.wholeGameContext().topicDependencies()) {
            Integer prerequisite = topicPositions.get(dependency.prerequisiteTopicKey());
            Integer dependent = topicPositions.get(dependency.dependentTopicKey());
            if (prerequisite == null || dependent == null || prerequisite >= dependent) {
                throw new IllegalArgumentException("persisted whole-game dependency conflicts with chapter order");
            }
        }
    }

    private static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
