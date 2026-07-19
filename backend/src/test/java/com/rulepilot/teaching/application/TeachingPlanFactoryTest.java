package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

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
                        topic("build-the-solar-system", "Build the rotating solar system", List.of("setup")),
                        topic("launch-and-listen", "Launch probes and listen for signals", List.of("core_loop")),
                        topic("close-the-fifth-round", "Close round five and score", List.of("end", "scoring"))));

        var plan = new TeachingPlanFactory().create(UUID.randomUUID(), 4, 3, 30, "player", outline);

        assertThat(plan.gameTitle()).isEqualTo("SETI");
        assertThat(plan.sections()).extracting(section -> section.title()).containsExactly(
                "Build the rotating solar system", "Launch probes and listen for signals", "Close round five and score");
        assertThat(plan.sections()).extracting(section -> section.topicKey()).doesNotContain("objective", "components");
    }

    private TopicDraft topic(String key, String title, List<String> tags) {
        return new TopicDraft(key, title, "Explain " + title, true, List.of(title), tags);
    }
}
