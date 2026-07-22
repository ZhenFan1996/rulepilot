package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class VersionedAgentPromptsTest {

    @Test
    void loadsVersionedContractsWithEvidenceAndInjectionBoundaries() throws Exception {
        VersionedAgentPrompts prompts = new VersionedAgentPrompts(
                resource("teaching-agent-v16-system.txt"),
                resource("teaching-agent-v17-fidelity-system.txt"),
                resource("teaching-agent-v9-user.txt"),
                resource("teaching-outline-v6-system.txt"),
                resource("teaching-outline-v7-fidelity-system.txt"),
                resource("teaching-outline-v3-user.txt"),
                resource("rule-answer-agent-v6-system.txt"),
                resource("rule-answer-agent-v7-fidelity-system.txt"),
                resource("rule-answer-agent-v4-user.txt"),
                resource("rule-answer-retrieval-rewrite-v1-system.txt"),
                resource("rule-answer-retrieval-rewrite-v1-user.txt"),
                resource("content-critic-v7-system.txt"),
                resource("content-critic-v8-fidelity-system.txt"),
                resource("atomic-content-critic-v3-system.txt"),
                resource("objective-coverage-critic-v3-system.txt"),
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
                        "source terminology consistently",
                        "scope gate",
                        "solo rival",
                        "hypothetical label",
                        "Never substitute an emoji",
                        "describe it generically as the displayed reward",
                        "separate concepts",
                        "unused temporary points persist",
                        "ownership quantifiers",
                        "win condition",
                        "X or Y",
                        "Preserve relative rules as relative rules",
                        "distinct Chinese names",
                        "asymmetric game",
                        "first-game walkthrough",
                        "maximum step count",
                        "never append a seventh step",
                        "strongest complete rule sentence",
                        "indispensable visual relationships for the objective",
                        "printed order exactly",
                        "complete objective coverage",
                        "Do not output analysis");
        assertThat(prompts.teachingUser())
                .contains(
                        "{objective}",
                        "{coverage}",
                        "{requiredRules}",
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
                        "rulebook's source language",
                        "exact printed headings",
                        "objective",
                        "visualEvidenceRecommended",
                        "page artwork",
                        "page prose is sufficient",
                        "page-by-page coverage audit",
                        "later pages",
                        "exact subject of every state transition",
                        "either A or B",
                        "Every explicit walkthrough or Example Round",
                        "asymmetric factions",
                        "walkthrough");
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
                        "default next-actor rule",
                        "unable to act",
                        "unused piece can remain for a later turn",
                        "unlisted component",
                        "empty exceptions list",
                        "Every explanatory sentence",
                        "matching",
                        "same type");
        assertThat(prompts.answerUser())
                .contains("{questionType}", "{learningIntent}", "{gamePhase}", "{playerCount}");
        assertThat(prompts.answerRetrievalRewriteSystem()).contains("retrieval", "English phrase");
        assertThat(prompts.answerRetrievalRewriteUser()).contains("{question}");
        assertThat(prompts.criticSystem())
                .contains(
                        "MISSING_EXCEPTION",
                        "MISSING_CRITICAL_RULE",
                        "visual description",
                        "outside knowledge",
                        "causal bridge",
                        "resource ledger",
                        "starting inventory",
                        "cost in one unit",
                        "named object",
                        "negation",
                        "heading ownership",
                        "player turn (回合)",
                        "default next-actor rules",
                        "left active play",
                        "invented baseline",
                        "Never emit an issue as an audit note",
                        "semantic rule meaning",
                        "combined evidence set",
                        "Recount every enumerated list",
                        "invented species",
                        "same official term",
                        "state-transition subjects word by word",
                        "exclusive branches",
                        "reverses two explicitly ordered state changes",
                        "Re-audit every corrected claim",
                        "at most 12 issues",
                        "ATOMIC_CONFIRMATION",
                        "faithful contextual translation",
                        "at most 160 characters");
        assertThat(prompts.atomicCriticSystem())
                .contains(
                        "one or more generated claims",
                        "Judge each claim independently",
                        "types listed for that position",
                        "combined evidence set",
                        "semantic meaning",
                        "guessed symbol",
                        "missing glyph",
                        "nearby sidebar",
                        "return an empty issues list");
        assertThat(prompts.objectiveCoverageCriticSystem())
                .contains(
                        "objective coverage",
                        "complete generated board-game lesson",
                        "MISSING_CRITICAL_RULE",
                        "X or Y",
                        "generic parent heading",
                        "selected evidence");
        assertThat(prompts.criticUser()).contains("{mode}", "{objective}", "{coverage}", "{claims}", "{evidence}");
        assertThat(prompts.structuredOutputRepair()).contains("Regenerate", "schema-valid object only");
    }

    private ClassPathResource resource(String name) {
        return new ClassPathResource("prompts/" + name);
    }
}
