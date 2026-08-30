package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanEntityContextMappingTest {

    @Test
    void productionEntityJsonRoundTripDoesNotLoseTheWholeGameContext() {
        var context = new TeachingPlan.WholeGameContext(
                List.of(new TeachingPlan.TopicDependency(
                        "first-topic", "second-topic", "先建立第一项关系。")),
                List.of("仍需说明一个可见缺口。"));
        TeachingPlan original = new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "自主理解规则关系",
                "Opaque game",
                "两个章节按依赖顺序组织。",
                context,
                List.of(
                        new TeachingPlan.PlannedSection(
                                1, "first-topic", "第一章", "讲清第一项。", true, false,
                                List.of("R-one"), List.of("whole_game_context_v1"), List.of(2)),
                        new TeachingPlan.PlannedSection(
                                2, "second-topic", "第二章", "讲清第二项。", true, false,
                                List.of("R-two"), List.of("whole_game_context_v1"), List.of(3))),
                "player",
                Instant.parse("2026-08-16T09:00:00Z"));

        TeachingPlan restored = TeachingPlanPersistenceRoundTrip.serializeAndReload(original);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.wholeGameContext().unresolvedTopics())
                .containsExactly("仍需说明一个可见缺口。");
    }

    @Test
    void readsOldStoredContextByIgnoringRetiredCompletenessObjects() {
        var context = TeachingPlanEntity.readContext("""
                {"summary":"legacy","concepts":[{"conceptId":"old"}],"evidenceBound":true,
                 "topicDependencies":[{"prerequisiteTopicKey":"setup","dependentTopicKey":"play",
                 "reason":"setup first"}],"unresolvedTopics":["scoring still unknown"]}
                """, "legacy premise");

        assertThat(context.topicDependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.prerequisiteTopicKey()).isEqualTo("setup");
            assertThat(dependency.dependentTopicKey()).isEqualTo("play");
        });
        assertThat(context.unresolvedTopics()).containsExactly("scoring still unknown");
    }
}
