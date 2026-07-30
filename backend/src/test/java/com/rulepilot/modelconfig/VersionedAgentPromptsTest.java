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
                resource("teaching-agent-v18-visual-fit-system.txt"),
                resource("teaching-agent-v19-player-language-system.txt"),
                resource("teaching-agent-v20-visual-output-contract-system.txt"),
                resource("teaching-agent-v21-complete-instructions-system.txt"),
                resource("teaching-agent-v22-ordered-procedure-fidelity-system.txt"),
                resource("teaching-agent-v23-chapter-scope-map-system.txt"),
                resource("teaching-agent-v24-actor-and-example-fidelity-system.txt"),
                resource("teaching-agent-v25-conditional-values-fidelity-system.txt"),
                resource("teaching-agent-v26-conditional-scope-and-tie-fidelity-system.txt"),
                resource("teaching-agent-v27-visible-observation-system.txt"),
                resource("teaching-agent-v9-user.txt"),
                resource("teaching-outline-v6-system.txt"),
                resource("teaching-outline-v7-fidelity-system.txt"),
                resource("teaching-outline-v8-visual-density-system.txt"),
                resource("teaching-outline-v9-core-evidence-system.txt"),
                resource("teaching-outline-v10-chapter-ownership-system.txt"),
                resource("teaching-outline-v11-visual-coverage-system.txt"),
                resource("teaching-outline-v3-user.txt"),
                resource("rule-answer-agent-v6-system.txt"),
                resource("rule-answer-agent-v7-fidelity-system.txt"),
                resource("rule-answer-agent-v8-direct-rulings-system.txt"),
                resource("rule-answer-agent-v9-prohibition-fidelity-system.txt"),
                resource("rule-answer-agent-v10-completeness-boundary-system.txt"),
                resource("rule-answer-agent-v11-scope-discipline-system.txt"),
                resource("rule-answer-agent-v12-direct-clause-citations-system.txt"),
                resource("rule-answer-agent-v13-grounded-application-system.txt"),
                resource("rule-answer-agent-v14-natural-language-condition-system.txt"),
                resource("rule-answer-agent-v16-fallback-branch-fidelity-system.txt"),
                resource("rule-answer-agent-v17-correctable-revision-system.txt"),
                resource("rule-answer-agent-v18-counterfactual-follow-up-system.txt"),
                resource("rule-answer-agent-v4-user.txt"),
                resource("rule-answer-retrieval-rewrite-v1-system.txt"),
                resource("rule-answer-retrieval-rewrite-v1-user.txt"),
                resource("content-critic-v7-system.txt"),
                resource("content-critic-v8-fidelity-system.txt"),
                resource("content-critic-v9-answer-scope-system.txt"),
                resource("content-critic-v10-lesson-structure-system.txt"),
                resource("content-critic-v11-actor-and-example-fidelity-system.txt"),
                resource("atomic-content-critic-v3-system.txt"),
                resource("objective-coverage-critic-v3-system.txt"),
                resource("content-critic-v4-user.txt"),
                resource("structured-output-repair-v1.txt"),
                resource("lesson-localization-v1-system.txt"),
                resource("lesson-localization-v1-user.txt"));

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
                        "crop earns its place",
                        "block of example prose",
                        "natural Simplified Chinese",
                        "Player-language revision v19",
                        "Visual-output revision v20",
                        "Complete-instruction revision v21",
                        "unanswered alternative",
                        "Ordered-procedure fidelity revision v22",
                        "must do B, then may do C or D",
                        "Chapter-scope-map revision v23",
                        "Actor-and-example fidelity revision v24",
                        "your figures",
                        "Conditional-value fidelity revision v25",
                        "player-count/value condition",
                        "Conditional-scope and tie fidelity revision v26",
                        "tie-break chain",
                        "Visible-observation revision v27",
                        "visibleDescription",
                        "Preserve timing boundaries exactly",
                        "fictional separate final-scoring phase",
                        "Fully teach only the current section objective",
                        "camel-case property names",
                        "visualCaption",
                        "A named state or relationship is not its definition",
                        "Citation IDs are machine fields only",
                        "negative evidence statement is not a game rule",
                        "rest of the page hidden",
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
                        "{chapterScope}",
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
                        "walkthrough",
                        "several distinct player needs",
                        "Three to six visual topics",
                        "Core-evidence revision v9",
                        "storage sheet",
                        "Chapter-ownership revision v10",
                        "one primary teaching owner",
                        "complete cleanup procedure",
                        "chapter-boundary audit",
                        "Visual-coverage revision v11",
                        "untrusted navigation context",
                        "missing visual catalog",
                        "end trigger, winner, victory condition");
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
                        "same type",
                        "Direct-ruling revision v8",
                        "full decisive condition in the question itself",
                        "Initial setup or inventory evidence",
                        "Prohibition-fidelity revision v9",
                        "does not bypass a prohibited terrain",
                        "Evidence-completeness revision v10",
                        "Selected evidence is not a complete rulebook index",
                        "Scope-discipline revision v11",
                        "Direct-clause citation revision v12",
                        "translated or rewritten query must not displace",
                        "enumerated cleanup check",
                        "loop-prevention rule",
                        "player's wording is not a component definition",
                        "Grounded-application revision v13",
                        "GROUNDED_APPLICATION",
                        "not private reasoning",
                        "Natural-language condition revision v14",
                        "not required to use the rulebook's label",
                        "Fallback-branch fidelity revision v16",
                        "no-op",
                        "Correctable-revision completion revision v17",
                        "answerable=true",
                        "Counterfactual follow-up coverage revision v18",
                        "even when the immediate verdict is no");
        assertThat(prompts.answerUser())
                .contains(
                        "{questionType}",
                        "{learningIntent}",
                        "{lessonSection}",
                        "{outputLanguage}",
                        "A named state or relationship is not its definition")
                .doesNotContain("{gamePhase}", "{playerCount}", "{activeExpansionCount}");
        assertThat(prompts.lessonLocalizationSystem())
                .contains("constrained translation", "exact section and step positions", "Do not add rules");
        assertThat(prompts.lessonLocalizationUser()).contains("{targetLanguage}", "{section}");
        assertThat(prompts.answerRetrievalRewriteSystem())
                .contains("retrieval", "English phrase", "search phrases", "untrusted data", "Return JSON only");
        assertThat(prompts.answerRetrievalRewriteUser())
                .contains("{question}", "{previousQuestion}", "{lessonSection}");
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
                        "Answer-scope revision v9",
                        "loop-prevention rule",
                        "Lesson-structure revision v10",
                        "CHAPTER_SCOPE_DUPLICATION",
                        "earlier chapter expands",
                        "POST_PUBLICATION_STRUCTURE",
                        "Prioritize a chapter whose title or objective says overview",
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
        assertThat(prompts.criticSystem()).contains(
                "Actor-and-example fidelity revision v11",
                "temporary illustration",
                "merely related padding citations");
        assertThat(prompts.lessonStructureCriticSystem())
                .contains("POST_PUBLICATION_STRUCTURE", "only CHAPTER_SCOPE_DUPLICATION");
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
