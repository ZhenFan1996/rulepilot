package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TeachingPlanFactory {

    private static final int MAX_TOPICS = 16;
    private static final Set<String> CORE_COVERAGE = Set.of("setup", "core_loop", "end", "scoring");

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
                            normalizedQueries(topic.retrievalQueries()),
                            normalizedTags(topic.coverageTags()),
                            topic.sourcePageNumbers());
                })
                .toList();
        return new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                learningGoal,
                outline.gameTitle(),
                outline.premise(),
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
        Set<String> keys = new HashSet<>();
        Set<String> covered = new HashSet<>();
        for (int index = 0; index < outline.topics().size(); index++) {
            var topic = outline.topics().get(index);
            if (!keys.add(normalizedKey(topic.key(), index + 1))) {
                throw new IllegalArgumentException("teaching topic keys must be unique");
            }
            normalizedQueries(topic.retrievalQueries());
            covered.addAll(normalizedTags(topic.coverageTags()));
        }
        if (!covered.containsAll(CORE_COVERAGE)) {
            throw new IllegalArgumentException("teaching outline omitted setup, core loop, ending, or scoring coverage");
        }
    }

    private String normalizedKey(String value, int position) {
        String key = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return key.isBlank() ? "topic-" + position : key.substring(0, Math.min(key.length(), 80));
    }

    private List<String> normalizedTags(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> canonicalTag(value.toLowerCase(Locale.ROOT).replace('-', '_').strip()))
                .distinct()
                .toList();
    }

    private List<String> normalizedQueries(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("every teaching topic needs at least one retrieval question");
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .filter(value -> value.length() <= 300)
                .distinct()
                .limit(4)
                .toList();
    }

    private String canonicalTag(String tag) {
        return switch (tag) {
            case "game_setup", "table_setup" -> "setup";
            case "turn", "turns", "turn_structure", "round_structure", "gameplay", "actions" -> "core_loop";
            case "game_end", "end_game", "end_conditions", "ending" -> "end";
            case "final_scoring", "score", "scores" -> "scoring";
            default -> tag;
        };
    }
}
