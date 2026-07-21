package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanFactoryTest {

    @Test
    void preservesTheModelsGameSpecificTopicsInsteadOfExpandingAFixedTemplate() {
        var outline = new OutlineDraft(
                "SETI",
                "Lead a scientific institution searching for alien life.",
                List.of(
                        topic("build-the-solar-system", "Build the rotating solar system", true, List.of("setup")),
                        topic("launch-and-listen", "Launch probes and listen for signals", false, List.of("core_loop")),
                        topic("close-the-fifth-round", "Close round five and score", false, List.of("end", "scoring"))));

        var plan = new TeachingPlanFactory().create(UUID.randomUUID(), 4, 3, 30, "player", outline);

        assertThat(plan.gameTitle()).isEqualTo("SETI");
        assertThat(plan.sections()).extracting(section -> section.title()).containsExactly(
                "Build the rotating solar system", "Launch probes and listen for signals", "Close round five and score");
        assertThat(plan.sections()).extracting(section -> section.topicKey()).doesNotContain("objective", "components");
        assertThat(plan.sections()).extracting(section -> section.visualEvidenceRecommended())
                .containsExactly(true, false, false);
    }

    @Test
    void rejectsInvalidTopicShapeAtTheModelBoundarySoStructuredRepairCanRun() {
        assertThatThrownBy(() -> new TopicDraft(
                        "setup",
                        "Setup",
                        "x".repeat(601),
                        true,
                        true,
                        List.of("setup"),
                        List.of("setup")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("teaching outline topic is invalid");
    }

    @Test
    void accepts_omitted_optional_audit_labels_so_the_plan_factory_can_apply_its_existing_contract() {
        var topic = new TopicDraft(
                null, "开局", "完成开局设置。", true, true, List.of("Starting Set-up"), null);

        assertThat(topic.key()).isBlank();
        assertThat(topic.coverageTags()).isEmpty();
    }

    private TopicDraft topic(String key, String title, boolean visualEvidenceRecommended, List<String> tags) {
        return new TopicDraft(
                key, title, "Explain " + title, true, visualEvidenceRecommended, List.of(title), tags);
    }
}
