package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class VersionedAgentPromptsTest {

    @Test
    void loadsVersionedContractsWithEvidenceAndInjectionBoundaries() throws Exception {
        VersionedAgentPrompts prompts = new VersionedAgentPrompts(
                resource("teaching-agent-v4-system.txt"),
                resource("teaching-agent-v4-user.txt"),
                resource("rule-answer-agent-v2-system.txt"),
                resource("rule-answer-agent-v2-user.txt"),
                resource("content-critic-v3-system.txt"),
                resource("content-critic-v3-user.txt"),
                resource("structured-output-repair-v1.txt"));

        assertThat(prompts.teachingSystem())
                .contains(
                        "untrusted data",
                        "directly support the whole step",
                        "continuity data is not evidence",
                        "maximum step count",
                        "Do not output analysis");
        assertThat(prompts.teachingUser())
                .contains(
                        "{objective}",
                        "{coverage}",
                        "{totalDuration}",
                        "{sectionDuration}",
                        "{maxSteps}",
                        "{continuity}",
                        "{evidence}");
        assertThat(prompts.answerSystem())
                .contains("set answerable to false", "Do not answer from prior knowledge", "gameplay context");
        assertThat(prompts.answerUser()).contains("{questionType}", "{gamePhase}", "{playerCount}");
        assertThat(prompts.criticSystem())
                .contains("MISSING_EXCEPTION", "MISSING_CRITICAL_RULE", "visual description", "outside knowledge");
        assertThat(prompts.criticUser()).contains("{objective}", "{coverage}", "{claims}", "{evidence}");
        assertThat(prompts.structuredOutputRepair()).contains("Regenerate", "schema-valid object only");
    }

    private ClassPathResource resource(String name) {
        return new ClassPathResource("prompts/" + name);
    }
}
