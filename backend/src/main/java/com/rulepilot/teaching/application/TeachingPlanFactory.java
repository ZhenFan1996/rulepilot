package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingPlan.TopicDependency;
import com.rulepilot.teaching.domain.TeachingPlan.WholeGameContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TeachingPlanFactory {

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
                            true,
                            topic.visualEvidenceRecommended(),
                            List.of(topic.objective()),
                            List.of(),
                            topic.sourcePageNumbers(),
                            topic.visualSourcePageNumbers());
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
                || outline.topics().isEmpty()) {
            throw new IllegalArgumentException("model did not produce a usable teaching outline");
        }
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < outline.topics().size(); index++) {
            var topic = outline.topics().get(index);
            if (!keys.add(normalizedKey(topic.key(), index + 1))) {
                throw new IllegalArgumentException("teaching topic keys must be unique");
            }
        }
    }

    private String normalizedKey(String value, int position) {
        return value == null || value.isBlank() ? "topic-" + position : value;
    }

    private WholeGameContext wholeGameContext(OutlineDraft outline) {
        List<TopicDependency> dependencies = outline.topicDependencies().stream()
                .map(dependency -> new TopicDependency(
                        normalizedTopicKey(outline, dependency.prerequisiteTopicKey()),
                        normalizedTopicKey(outline, dependency.dependentTopicKey()),
                        dependency.reason()))
                .toList();
        return new WholeGameContext(dependencies, outline.unresolvedTopics());
    }

    private String normalizedTopicKey(OutlineDraft outline, String key) {
        return java.util.stream.IntStream.range(0, outline.topics().size())
                .filter(index -> outline.topics().get(index).key().equals(key))
                .mapToObj(index -> normalizedKey(key, index + 1))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("whole-game context references an unknown topic"));
    }

}
