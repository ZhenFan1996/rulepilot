package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.GlobalConcept;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingPlan.TopicDependency;
import com.rulepilot.teaching.domain.TeachingPlan.WholeGameContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TeachingPlanFactory {

    private static final int MAX_TOPICS = 16;

    public TeachingPlan create(
            UUID documentVersionId,
            String createdBy,
            OutlineDraft outline) {
        return create(
                documentVersionId,
                null,
                createdBy,
                outline);
    }

    public TeachingPlan create(
            UUID documentVersionId,
            String learningGoal,
            String createdBy,
            OutlineDraft outline) {
        validate(outline);
        Set<String> keys = new HashSet<>();
        List<PlannedSection> topics = java.util.stream.IntStream.range(0, outline.topics().size())
                .mapToObj(index -> {
                    var topic = outline.topics().get(index);
                    String key = normalizedKey(topic.key(), index + 1);
                    if (!keys.add(key)) {
                        throw new IllegalArgumentException("teaching topic keys must be unique");
                    }
                    return new PlannedSection(
                            index + 1,
                            key,
                            topic.title(),
                            topic.objective(),
                            topic.required(),
                            topic.visualEvidenceRecommended(),
                            plannedRetrievalContracts(outline, topic),
                            planTags(outline, topic),
                            topic.sourcePageNumbers());
                })
                .toList();
        return new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                learningGoal,
                outline.gameTitle(),
                outline.premise(),
                wholeGameContext(outline),
                topics,
                createdBy,
                Instant.now());
    }

    void validate(OutlineDraft outline) {
        if (outline == null || outline.gameTitle() == null || outline.gameTitle().isBlank()
                || outline.premise() == null || outline.premise().isBlank()
                || outline.topics().isEmpty() || outline.topics().size() > MAX_TOPICS) {
            throw new IllegalArgumentException("model did not produce a usable teaching outline");
        }
        TeachingSourceCoverageContract.validateStructure(outline);
        if (!outline.wholeGameUnderstanding().concepts().isEmpty()) {
            TeachingWholeGameUnderstandingPolicy.validateComplete(outline);
        }
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < outline.topics().size(); index++) {
            var topic = outline.topics().get(index);
            if (!keys.add(normalizedKey(topic.key(), index + 1))) {
                throw new IllegalArgumentException("teaching topic keys must be unique");
            }
            boolean ownsSourceSlots = outline.sourceCoverageSlots().stream()
                    .anyMatch(slot -> slot.ownerTopicKey().equals(topic.key()));
            if (!ownsSourceSlots) normalizedQueries(topic.retrievalQueries());
        }
    }

    private List<String> plannedRetrievalContracts(
            OutlineDraft outline, com.rulepilot.teaching.TeachingOutlineModel.TopicDraft topic) {
        List<com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft> ownedSlots =
                outline.sourceCoverageSlots().stream()
                        .filter(slot -> slot.ownerTopicKey().equals(topic.key()))
                        .toList();
        return ownedSlots.isEmpty()
                ? normalizedQueries(topic.retrievalQueries())
                : TeachingUnitContract.encodeUnits(ownedSlots);
    }

    private String normalizedKey(String value, int position) {
        return value == null || value.isBlank() ? "topic-" + position : value;
    }

    private List<String> normalizedTags(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> planTags(OutlineDraft outline, com.rulepilot.teaching.TeachingOutlineModel.TopicDraft topic) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(normalizedTags(topic.coverageTags()));
        tags.addAll(TeachingSourceCoverageContract.metadataForTopic(outline, topic));
        if (!outline.wholeGameUnderstanding().concepts().isEmpty()) {
            tags.add(TeachingWholeGameUnderstandingPolicy.CONTRACT_TAG);
        }
        return List.copyOf(tags);
    }

    private WholeGameContext wholeGameContext(OutlineDraft outline) {
        var understanding = outline.wholeGameUnderstanding();
        if (understanding.concepts().isEmpty()) return WholeGameContext.legacy(outline.premise());
        List<GlobalConcept> concepts = understanding.concepts().stream()
                .map(concept -> new GlobalConcept(
                        concept.conceptId(),
                        concept.label(),
                        concept.explanation(),
                        concept.sourceIdentifiers(),
                        concept.sourcePageNumbers(),
                        concept.relatedTopicKeys().stream()
                                .map(key -> normalizedTopicKey(outline, key))
                                .toList(),
                        concept.prerequisiteConceptIds()))
                .toList();
        List<TopicDependency> dependencies = understanding.topicDependencies().stream()
                .map(dependency -> new TopicDependency(
                        normalizedTopicKey(outline, dependency.prerequisiteTopicKey()),
                        normalizedTopicKey(outline, dependency.dependentTopicKey()),
                        dependency.reason()))
                .toList();
        return new WholeGameContext(understanding.summary(), concepts, dependencies, true);
    }

    private String normalizedTopicKey(OutlineDraft outline, String key) {
        return java.util.stream.IntStream.range(0, outline.topics().size())
                .filter(index -> outline.topics().get(index).key().equals(key))
                .mapToObj(index -> normalizedKey(key, index + 1))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("whole-game context references an unknown topic"));
    }

    private List<String> normalizedQueries(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("every teaching topic needs at least one retrieval question");
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
