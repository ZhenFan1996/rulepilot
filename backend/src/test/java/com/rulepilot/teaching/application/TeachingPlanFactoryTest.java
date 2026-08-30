package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDependencyDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanFactoryTest {

    @Test
    void persistsAgentChosenChaptersDependenciesPagesAndConcreteUnresolvedTopics() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Learn the actual loop.",
                List.of(
                        new TopicDraft("setup", "Set up", "Prepare the table.", false, List.of(2, 3)),
                        new TopicDraft("repair", "Repair", "Repair damaged systems.", true, List.of(8, 9))),
                List.of(new TopicDependencyDraft("setup", "repair", "Set up before play.")),
                List.of("One external scenario sheet is unavailable"));

        var plan = new TeachingPlanFactory().create(UUID.randomUUID(), "owner", outline);

        assertThat(plan.sections()).extracting(section -> section.topicKey()).containsExactly("setup", "repair");
        assertThat(plan.sections().get(1).sourcePageNumbers()).containsExactly(8, 9);
        assertThat(plan.wholeGameContext().topicDependencies()).singleElement();
        assertThat(plan.wholeGameContext().unresolvedTopics())
                .containsExactly("One external scenario sheet is unavailable");
        assertThat(plan.sections()).allSatisfy(section -> assertThat(section.coverageTags()).isEmpty());
    }
}
