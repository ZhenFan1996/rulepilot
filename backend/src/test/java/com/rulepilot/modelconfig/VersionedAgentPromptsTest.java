package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class VersionedAgentPromptsTest {

    @Test
    void loadsVersionedContractsWithEvidenceAndInjectionBoundaries() throws Exception {
        VersionedAgentPrompts prompts = new VersionedAgentPrompts(
                resource("teaching-agent-v8-system.txt"),
                resource("teaching-agent-v8-user.txt"),
                resource("teaching-outline-v2-system.txt"),
                resource("teaching-outline-v2-user.txt"),
                resource("rule-answer-agent-v4-system.txt"),
                resource("rule-answer-agent-v4-user.txt"),
                resource("content-critic-v5-system.txt"),
                resource("content-critic-v4-user.txt"),
                resource("structured-output-repair-v1.txt"));

        assertThat(prompts.teachingSystem())
                .contains(
                        "untrusted data",
                        "directly support the whole step",
                        "short references E1",
                        "continuity data is not evidence",
                        "visualCitationIds",
                        "attached page",
                        "icon-to-label",
                        "PDF extraction markers",
                        "win condition",
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
                        "{evidence}",
                        "{visualEvidenceAvailable}",
                        "{visualPages}",
                        "{repair}");
        assertThat(prompts.teachingOutlineSystem())
                .contains("game-specific lesson", "core_loop", "retrieval query", "objective");
        assertThat(prompts.answerSystem())
                .contains(
                        "set answerable to false",
                        "prior knowledge",
                        "SIMPLIFY",
                        "EXAMPLE",
                        "designer intent",
                        "repeated use",
                        "MAIN ACTIONS",
                        "main column",
                        "player turn",
                        "Pass/跳过",
                        "empty exceptions list");
        assertThat(prompts.answerUser())
                .contains("{questionType}", "{learningIntent}", "{gamePhase}", "{playerCount}");
        assertThat(prompts.criticSystem())
                .contains(
                        "MISSING_EXCEPTION",
                        "MISSING_CRITICAL_RULE",
                        "visual description",
                        "outside knowledge",
                        "causal bridge",
                        "resource ledger",
                        "negation",
                        "heading ownership",
                        "player turn (回合)",
                        "invented baseline");
        assertThat(prompts.criticUser()).contains("{objective}", "{coverage}", "{claims}", "{evidence}");
        assertThat(prompts.structuredOutputRepair()).contains("Regenerate", "schema-valid object only");
    }

    private ClassPathResource resource(String name) {
        return new ClassPathResource("prompts/" + name);
    }
}
