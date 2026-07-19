package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class VersionedAgentPromptsTest {

    @Test
    void loadsVersionedContractsWithEvidenceAndInjectionBoundaries() throws Exception {
        VersionedAgentPrompts prompts = new VersionedAgentPrompts(
                resource("teaching-agent-v10-system.txt"),
                resource("teaching-agent-v8-user.txt"),
                resource("teaching-outline-v3-system.txt"),
                resource("teaching-outline-v3-user.txt"),
                resource("rule-answer-agent-v4-system.txt"),
                resource("rule-answer-agent-v4-user.txt"),
                resource("content-critic-v6-system.txt"),
                resource("atomic-content-critic-v1-system.txt"),
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
                        "visualFocus",
                        "0-1000",
                        "PDF extraction markers",
                        "scope gate",
                        "solo rival",
                        "hypothetical label",
                        "Never substitute an emoji",
                        "separate concepts",
                        "inclusive ownership",
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
                .contains(
                        "game-specific lesson",
                        "core_loop",
                        "retrieval query",
                        "objective",
                        "visualEvidenceRecommended",
                        "page artwork",
                        "page prose is sufficient");
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
                        "starting inventory",
                        "costs one extra movement",
                        "asteroid",
                        "negation",
                        "heading ownership",
                        "player turn (回合)",
                        "invented baseline",
                        "Never emit an issue as an audit note",
                        "semantic rule meaning",
                        "combined evidence set",
                        "ATOMIC_CONFIRMATION",
                        "credit/credits=信用点",
                        "at most 160 characters");
        assertThat(prompts.atomicCriticSystem())
                .contains("combined evidence set", "semantic meaning", "asteroid space", "return an empty issues list");
        assertThat(prompts.criticUser()).contains("{mode}", "{objective}", "{coverage}", "{claims}", "{evidence}");
        assertThat(prompts.structuredOutputRepair()).contains("Regenerate", "schema-valid object only");
    }

    private ClassPathResource resource(String name) {
        return new ClassPathResource("prompts/" + name);
    }
}
