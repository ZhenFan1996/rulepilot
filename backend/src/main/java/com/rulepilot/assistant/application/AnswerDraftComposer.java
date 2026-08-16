package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Produces one prepared, evidence-backed answer draft through the bounded model and repair workflow.
 *
 * <p>It cannot retrieve evidence, publish an answer, cache a result, or ask the post-publication Critic.</p>
 */
final class AnswerDraftComposer {

    private final AnswerModelGateway modelGateway;

    AnswerDraftComposer(AnswerModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    Result compose(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest) {
        ModelDraft draft;
        int modelRepairs = 0;
        try {
            draft = modelGateway.compose(assistantRunId, username, gameSessionId, modelRequest);
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_TIMEOUT,
                    "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答生成结果未通过结构或引用校验。");
        }
        if (draft == null) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答生成结果未通过结构或引用校验。");
        }
        draft = AnswerStructuredDraftPolicy.retainSelected(modelRequest, draft).draft();
        if (!draft.answerable()) {
            draft = reconsiderEvidenceBackedAbstention(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            modelRepairs++;
            if (draft != null) {
                draft = AnswerStructuredDraftPolicy.retainSelected(modelRequest, draft).draft();
            }
        }
        if (draft == null || !draft.answerable()) {
            return Result.failure(
                    AnswerStatus.INSUFFICIENT_EVIDENCE,
                    "现有证据未能直接回答这个问题。");
        }
        draft = AnswerDraftPublicationPolicy.removePeripheralEndgameCitations(modelRequest, draft);
        AnswerPlayerFacingRepairPolicy.RepairPlan playerFacingRepair =
                AnswerPlayerFacingRepairPolicy.planFor(modelRequest, draft);
        if (playerFacingRepair.required()) {
            if (modelRepairs > 0) {
                return Result.failure(
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "回答在一次有针对性的修订后仍包含内部标记。");
            }
            try {
                draft = revisePlayerFacingDraft(
                        assistantRunId, username, gameSessionId, modelRequest, draft, playerFacingRepair);
                modelRepairs++;
            } catch (RuleAnswerModelTimeoutException exception) {
                return Result.failure(
                        AnswerStatus.MODEL_TIMEOUT,
                        "视觉规则消歧超时，可以稍后重试或直接查看规则引用。");
            } catch (RuntimeException exception) {
                return Result.failure(
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "回答修订结果未通过结构校验。");
            }
            if (draft == null || !draft.answerable()) {
                return Result.failure(
                        AnswerStatus.INSUFFICIENT_EVIDENCE,
                        AnswerRepairOutcomePolicy.insufficientRepairMessage(playerFacingRepair.feedback()));
            }
            draft = AnswerStructuredDraftPolicy.retainSelected(modelRequest, draft).draft();
            Optional<AnswerRepairOutcomePolicy.PublicationFailure> failure =
                    AnswerRepairOutcomePolicy.publicationFailure(modelRequest, draft);
            if (failure.isPresent()) {
                return Result.failure(failure.get().status(), failure.get().message());
            }
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, draft);
        if (!preparation.ready()) {
            return Result.failure(preparation.failureStatus(), preparation.failureMessage());
        }
        return Result.ready(preparation.draft(), preparation.warnings(), modelRepairs);
    }

    Result repairAfterPublicationFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The draft failed the final schema or citation validation.",
                            "Return a complete answer using only citationIds present in supplied evidence.",
                            "Keep shortVerdict nonblank, one or two plain sentences, and at most 200 characters; keep explanation nonblank and at most 1500 characters; keep citationIds nonempty.",
                            "Preserve structured fields that already satisfy their contract unless they caused the stated validation failure. In particular, keep each termDefinitions definition at most 600 characters and boundary at most 400 characters.",
                            "For a SOURCE request or a question asking where the rule appears, use one or two direct citationIds, keep the most direct clause first, answer the question in shortVerdict, and explain that clause in plain player language instead of redirecting to a page.",
                            "For a SOURCE request, do not add ownership or hand location, and do not change 'during a turn or phase' into 'at the end or completion of' that turn or phase unless the cited clause says so.",
                            "For a SOURCE request, preserve the exact grammatical number of every capitalized official term from the cited clause; do not invent a singular or plural form.",
                            "For a SOURCE request, do not claim that the rule or excerpt has no other restriction, condition, exception, limit, or exact timing; explain only what it affirmatively states.",
                            "For a can/may/allowed question, answer can or cannot directly in shortVerdict and preserve the cited permission or prohibition direction, including every stated condition and exception.",
                            "Remove every claim that the cited excerpts do not directly support."),
                    "repairPublicationValidation",
                    "Final citation validation failure repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_TIMEOUT,
                    "回答修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答修订结果未通过结构校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答修订结果未通过结构或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation =
                AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterCalculationFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "A requested arithmetic derivation used an unsupported operand or invalid expression.",
                            "Use only +, -, *, /, parentheses, floor, ceil, min, or max.",
                            "Every numeric operand must appear in the current player question or cited evidence, and at least one operand must come from the current question.",
                            "If no grounded arithmetic is needed, return an empty calculations list and remove unsupported computed totals."),
                    "repairRuleCalculation",
                    "Unsupported rule calculation repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则计算修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则计算未通过输入来源或表达式校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则计算未通过输入来源或表达式校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterSituationCheckFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The live-table answer omitted or invalidly inferred a decisive situation requirement.",
                            "For each material cited prerequisite, return one situationChecks item with requirement, status, playerFact, and citationIds.",
                            "CONFIRMED or CONTRADICTED requires a short playerFact copied literally from the current question. NOT_PROVIDED requires an empty playerFact.",
                            "When any requirement is NOT_PROVIDED, give only a conditional or not-yet-determined verdict and ask for that fact. Never assume it."),
                    "repairRuleSituationCheck",
                    "Unsupported live-table state inference repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "局面条件核对超时，可以稍后重试或补充当前状态。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "局面条件未通过玩家输入或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "局面条件未通过玩家输入或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterWalkthroughFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        boolean dependencyTrace = modelRequest.context().learningIntent()
                == com.rulepilot.assistant.domain.LearningIntent.WHY;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    dependencyTrace
                            ? List.of(
                                    "The WHY answer omitted or invalidly structured its cited rule dependency trace.",
                                    "Return two to six walkthroughSteps linking only the evidenced prerequisite or trigger, explicit rule transition, and required consequence.",
                                    "Every dependency step must use RULE_ORDER and one to three citationIds that directly support that individual step.",
                                    "Explain mechanical dependency only. Never invent designer intent, fairness, balance, theme, strategy, purpose, or a causal arrow that the supplied evidence does not establish.",
                                    "Every number in a step must occur in that step's own cited evidence. Preserve the number's unit and role.",
                                    "Use ordinary player language, not formal-logic jargon. Each explanation must add the cited relationship and must not repeat its instruction." )
                            : List.of(
                                    "The procedural answer omitted or invalidly structured its player walkthrough.",
                                    "Return a bounded walkthroughSteps list. Each step needs instruction, explanation, orderBasis, and citationIds from supplied evidence.",
                                    "Use RULE_ORDER only when the cited rule establishes that sequence. Use EXPLANATION_ORDER when steps are split only to make the explanation easier to follow.",
                                    "Do not turn document order, bullet order, or your own teaching order into a mandatory gameplay sequence.",
                                    "Every number in a step must occur in that step's own cited evidence."),
                    dependencyTrace ? "repairRuleDependencyTrace" : "repairRuleWalkthrough",
                    dependencyTrace
                            ? "Unsupported rule dependency trace repaired"
                            : "Unsupported rule walkthrough repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT,
                    dependencyTrace
                            ? "规则因果链修订超时，可以稍后重试或直接查看规则引用。"
                            : "分步讲解修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT,
                    dependencyTrace
                            ? "规则因果链未通过前提、结果或逐步引用校验。"
                            : "分步讲解未通过顺序标记或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT,
                    dependencyTrace
                            ? "规则因果链未通过前提、结果或逐步引用校验。"
                            : "分步讲解未通过顺序标记或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterDecisionTableFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The branching answer omitted or invalidly structured its cited condition/outcome table.",
                            "Return at most six decisionBranches. Each item needs condition, outcome, basis, and one to three citationIds from supplied evidence.",
                            "Use EXPLICIT_RULE only when the cited passage states that condition-to-outcome relationship.",
                            "Use RULEBOOK_EXAMPLE only when the cited passage explicitly presents that case as an example; never promote an example into a universal rule.",
                            "Do not invent an unmentioned fallback, merge distinct conditions, or create model-authored examples in decisionBranches."),
                    "repairRuleDecisionTable",
                    "Unsupported rule decision table repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "条件分支修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "条件分支未通过结果来源或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "条件分支未通过结果来源或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterExceptionClauseFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked for exceptions or restrictions, but the draft omitted or invalidly structured its cited exceptionClauses.",
                            "Return at most six exceptionClauses. Each item needs condition, effect, and one to three citationIds from supplied evidence.",
                            "Each cited passage must directly establish that exact condition-to-effect relationship.",
                            "Do not treat a special-sounding heading, document order, or a rulebook example as an exception unless the evidence states the qualification.",
                            "Keep legacy exceptions empty when the same material is represented in exceptionClauses; do not duplicate player-facing content."),
                    "repairRuleExceptionList",
                    "Unsupported rule exception list repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "例外和限制修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "例外和限制未通过条件、效果或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "例外和限制未通过条件、效果或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterTermDefinitionFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked for a definition or distinction, but the draft omitted or invalidly structured its cited termDefinitions.",
                            "Return at most four termDefinitions. Each item needs a nonblank term (at most 120 characters), nonblank definition (at most 600 characters), boundary (at most 400 characters), and one to three citationIds from supplied evidence.",
                            "The cited passage must directly define the term. A mention, example, consequence, component label, or nearby relationship is not a definition.",
                            "Use boundary for an evidenced exclusion, scope limit, or contrast that prevents a likely confusion; otherwise return an empty string.",
                            "Keep each definition to one or two concise sentences and each boundary to one sentence. Delete full procedures, examples, consequences, and nearby rules.",
                            "For a comparison, define every requested term separately and make each distinction traceable to the cited definitions."),
                    "repairRuleTermDefinitions",
                    "Unsupported rule term definitions repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "术语定义修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "术语定义未通过定义边界或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "术语定义未通过定义边界或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterWorkedExampleFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked for an example, but the draft omitted or invalidly structured its cited workedExamples.",
                            "Return at most three workedExamples. Each item needs nonblank setup (at most 500 characters), action (at most 700), outcome (at most 500), basis, and one to three citationIds.",
                            "Use RULEBOOK_EXAMPLE only when the cited passage presents that actual case. Preserve every stated starting quantity, name, payment, gain, sequence, and final result exactly.",
                            "Use EVIDENCE_BOUND_ILLUSTRATION only with neutral placeholders and no invented number, name, resource, position, prerequisite, or outcome.",
                            "Preserve grammatical attachment: never turn an object used in locations shown by a reference into an object shown on that reference.",
                            "Preserve each number's unit and role: if N objects contribute M strength or points, never call them M objects in the summary or example.",
                            "Keep setup, action, and outcome separately understandable. Do not leave the requested example only in prose."),
                    "repairRuleWorkedExamples",
                    "Unsupported rule worked examples repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则示例修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则示例未通过起始状态、动作、结果或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则示例未通过起始状态、动作、结果或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterRulePriorityFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked whether two rules conflict or which one has priority, but the draft omitted a valid cited relationship result.",
                            "First compare actor, object, trigger, timing, mode, and condition. Return priorityResolutions only when evidence explicitly resolves a real conflict.",
                            "If the passages apply to different scopes and therefore do not conflict, leave priorityResolutions empty and return one conceptComparisons item. Use RULE_SCOPE unless a more specific ACTION_WINDOW, RESOURCE_FUNCTION, or STORAGE_STATUS basis applies.",
                            "For a non-conflict answer, shortVerdict must be self-contained: state that the rules do not conflict and summarize the differing condition, timing window, actor, object, or direction. A bare 'No conflict' is invalid. Preserve every official rule and step name exactly as written in the evidence.",
                            "A priorityResolutions item needs nonblank baseRule (at most 500 characters), competingRule (at most 500), resolution (at most 600), basis, and one to three citationIds. A conceptComparisons item needs both definitions, commonGround, keyDifference, practicalBoundary, basis, and citations for both passages.",
                            "Use EXPLICIT_OVERRIDE only when the source explicitly says one rule overrides or supersedes another.",
                            "Use IMPOSSIBILITY_PRIORITY only when the source explicitly gives impossible effects priority over possible effects.",
                            "Use CONFLICT_ONLY_OVERRIDE only when the source says the competing rule replaces the base rule on conflict but both remain required when compatible.",
                            "Never infer priority from headings, page order, a rule sounding more specific, theme, or a rulebook example. Do not invent a conflict merely because the player used that word."),
                    "repairRuleConflictCheck",
                    "Unsupported rule conflict check repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则冲突检查修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则冲突检查未通过适用范围、优先级或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则冲突检查未通过适用范围、优先级或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterTimingFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked how simultaneous effects are ordered, but the draft omitted or invalidly structured its cited timingResolutions.",
                            "Return at most three timingResolutions. Each item needs nonblank timingContext (at most 500 characters), resolutionOrder (at most 700), orderSource (at most 400), basis, and one to three citationIds.",
                            "Use CURRENT_PLAYER_CHOOSES only when the source explicitly assigns the ordering choice to the player taking the current turn. orderSource must name that current-turn player, and resolutionOrder must say that player chooses the order; a section title is not an order source.",
                            "Use PRINTED_TOP_TO_BOTTOM only when the source explicitly says same-timing effects resolve from top to bottom. Both resolutionOrder and orderSource must preserve top-to-bottom, and orderSource must identify the card or printed text.",
                            "Use NORMAL_TURN_ORDER only when the source explicitly mandates normal turn order. Both resolutionOrder and orderSource must name normal turn order; preserve any named starting role or player in resolutionOrder.",
                            "Never infer timing order from page layout, a card sounding newer or faster, initiative, clockwise convention, or model knowledge. Do not leave a requested timing ruling only in prose."),
                    "repairRuleTimingResolutions",
                    "Unsupported rule timing resolution repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "时序裁决修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "时序裁决未通过情境、顺序、来源或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "时序裁决未通过情境、顺序、来源或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterTieFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked how a tie is resolved, but the draft omitted or invalidly structured its cited tieResolutions.",
                            "Return at most three tieResolutions. Each item needs tieContext, one to six ordered resolutionSteps, finalOutcome, basis, and one to three citationIds from supplied evidence.",
                            "Use SINGLE_TIEBREAKER when the source states exactly one comparison or tie outcome; return exactly one step and do not invent what happens if it also ties.",
                            "Use ORDERED_TIEBREAKERS only when the source gives two or more criteria to check in sequence; preserve every criterion and the still-tied outcome.",
                            "Use RANK_REWARD_SHIFT only when a tie changes ranked rewards; state the result for every rank covered by the source, including no reward.",
                            "Do not use the normal number of awarded ranks to negate a rank-specific tie reward explicitly stated by the source.",
                            "Use POSITIONAL_PRIORITY only when the source explicitly names a positional fallback such as closest to the starting player; finalOutcome must name that exact priority.",
                            "For POSITIONAL_PRIORITY, resolutionSteps contain only the comparisons before the positional fallback. Put the positional fallback only in finalOutcome; do not duplicate it as a step.",
                            "Never invent a die roll, first-player win, shared victory, or turn-order fallback. Do not leave a requested tie ruling only in prose."),
                    "repairRuleTieResolutions",
                    "Unsupported tie-resolution ladder repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "平局判定修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "平局判定未通过步骤、最终结果或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "平局判定未通过步骤、最终结果或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterScopeFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player asked whether a rule applies to a stated player count, role presence, mode, or variant, but the draft omitted or invalidly structured scopeResolutions.",
                            "Return at most three scopeResolutions. Each item needs ruleContext, governingCondition, currentSituation, matchStatus, effect, basis, and one to three citationIds.",
                            "MATCHES_SCOPE or OUTSIDE_SCOPE requires currentSituation to restate the relevant scope fact from the current question. Use NEEDS_CONTEXT with currentSituation 'not provided' when that fact is missing.",
                            "Use PLAYER_COUNT, ROLE_PRESENCE, GAME_MODE, VARIANT_SELECTION, or PLAYER_COUNT_EXCEPTION only when the cited clause explicitly establishes that scope relationship.",
                            "For PLAYER_COUNT_EXCEPTION, preserve the special tied-rank reward even when an ordinary rank reward is normally unavailable at that player count.",
                            "Never infer the unstated complement of a condition, treat a recommendation as mandatory, or silently replace a missing role with a generic player."),
                    "repairRuleScopeResolutions",
                    "Unsupported rule applicability repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则适用范围修订超时，可以稍后重试或补充人数与模式。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则适用范围未通过条件、当前局面或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则适用范围未通过条件、当前局面或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterConceptComparisonFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player explicitly asked to distinguish two named rule concepts, but the draft omitted or invalidly structured conceptComparisons.",
                            "Return at most three conceptComparisons. Each item needs leftConcept, leftDefinition, rightConcept, rightDefinition, commonGround, keyDifference, practicalBoundary, basis, and one to three citationIds.",
                            "Use the two concepts named by the player and keep their asymmetric functions intact; do not collapse them into interchangeable paraphrases.",
                            "Use ACTION_WINDOW for concepts that happen in different turns, phases, or timing windows; RESOURCE_FUNCTION for tokens or resources with different uses; STORAGE_STATUS for different keep, stock, return, or limit rules; RULE_SCOPE when two apparent conflicts govern different actors, objects, modes, triggers, or conditions; otherwise use DEFINITION_BOUNDARY.",
                            "The practicalBoundary must tell the player when one concept applies and the other does not. Preserve every numeric limit or conversion exactly and cite evidence that states it.",
                            "Do not leave the requested distinction only in prose and do not infer a difference absent from the supplied evidence."),
                    "repairRuleConceptComparisons",
                    "Unsupported rule concept distinction repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则概念对比修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则概念对比未通过定义、边界或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则概念对比未通过定义、边界或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterRuleOptionFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The player explicitly asked what options, ways, types, sources, or available actions exist, but the draft omitted or invalidly structured ruleOptions.",
                            "Return one coherent set of two to eight ruleOptions. Each item needs the same decisionContext, selectionRule, and basis plus optionName, availabilityCondition, result, and one to three citationIds.",
                            "If cited evidence states an exact number of types, ways, or options, return exactly that many separately named items. Use each rulebook option name verbatim.",
                            "Use SOURCE_SELECTION for alternative sources, TIMING_CATALOG for named types with different timing windows, ALTERNATIVE_ACTION for distinct actions available for the same object, or EXCLUSIVE_CHOICE for another cited choose-one list.",
                            "selectionRule is one rule shared verbatim by the whole set. For English TIMING_CATALOG items, repeat exactly 'Each type may be played only in its stated timing window.' and put per-type timing only in availabilityCondition; do not invent a frequency claim.",
                            "Preserve whether the choice is mandatory, exactly one, optional, or repeatable. Keep replacement and no-replacement effects, exact costs, quantities, only-windows, and prohibitions in the structured option fields.",
                            "Lexical fidelity is required: when evidence says must, selectionRule must contain the literal word must or required; when it says multiple times, selectionRule or the affected result must contain multiple times or repeat.",
                            "Every number and each individual option must be directly supported by its citationIds; do not claim a complete list when the supplied evidence is incomplete."),
                    "repairRuleOptions",
                    "Incomplete cited rule option list repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则选项清单修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则选项清单未通过完整性、选择语义或引用校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则选项清单未通过完整性、选择语义或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    private ModelDraft reconsiderEvidenceBackedAbstention(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft) {
        return modelGateway.revise(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                AnswerEvidenceReconsiderationPolicy.feedbackFor(modelRequest),
                "reconsiderEvidenceBackedAbstention",
                "Evidence-backed table abstention reconsidered");
    }

    private ModelDraft revisePlayerFacingDraft(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            AnswerPlayerFacingRepairPolicy.RepairPlan repairPlan) {
        return modelGateway.revisePlayerFacing(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                repairPlan.feedback(),
                repairPlan.editableFields(),
                "repairPlayerFacingRuleAnswer",
                "Ambiguous visual identity or internal evidence language repaired");
    }

    record Result(
            ModelDraft draft,
            List<AnswerWarning> warnings,
            AnswerStatus failureStatus,
            String failureMessage,
            int modelRepairs) {

        static Result ready(ModelDraft draft, List<AnswerWarning> warnings) {
            return ready(draft, warnings, 1);
        }

        static Result ready(ModelDraft draft, List<AnswerWarning> warnings, int modelRepairs) {
            return new Result(draft, List.copyOf(warnings), null, null, modelRepairs);
        }

        static Result failure(AnswerStatus status, String message) {
            return new Result(null, List.of(), status, message, 0);
        }

        boolean ready() {
            return draft != null;
        }
    }
}
