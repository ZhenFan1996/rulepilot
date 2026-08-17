package com.rulepilot.assistant.adapter.out.persistence;

import com.rulepilot.assistant.application.GameSessionConversationRepository;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import com.rulepilot.assistant.domain.RuleTermDefinition;
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import com.rulepilot.assistant.domain.RulePriorityResolution;
import com.rulepilot.assistant.domain.RuleTimingResolution;
import com.rulepilot.assistant.domain.TimingOrderBasis;
import com.rulepilot.assistant.domain.RuleTieResolution;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import com.rulepilot.assistant.domain.RuleScopeResolution;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import com.rulepilot.assistant.domain.RuleConceptComparison;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.SituationCheckStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaGameSessionConversationRepository implements GameSessionConversationRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void save(GameSessionConversationTurn turn) {
        entityManager.persist(new GameSessionConversationTurnEntity(turn));
        entityManager.flush();
    }

    @Override
    public List<GameSessionConversationTurn> findRecent(UUID sessionId, String username, int limit) {
        List<GameSessionConversationTurn> recent = new ArrayList<>(entityManager.createQuery(
                        "select t from GameSessionConversationTurnEntity t "
                                + "where t.sessionId = :sessionId and t.createdBy = :username "
                                + "order by t.createdAt desc",
                        GameSessionConversationTurnEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("username", username)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(GameSessionConversationTurnEntity::toDomain)
                .toList());
        Collections.reverse(recent);
        return List.copyOf(recent);
    }

    @Override
    public Optional<GameSessionConversationTurn> findOwned(UUID turnId, UUID sessionId, String username) {
        List<GameSessionConversationTurnEntity> matches = entityManager.createQuery(
                        "select t from GameSessionConversationTurnEntity t "
                                + "where t.id = :turnId and t.sessionId = :sessionId and t.createdBy = :username",
                        GameSessionConversationTurnEntity.class)
                .setParameter("turnId", turnId)
                .setParameter("sessionId", sessionId)
                .setParameter("username", username)
                .setMaxResults(1)
                .getResultList();
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst().toDomain());
    }
}

@Entity(name = "GameSessionConversationTurnEntity")
@Table(name = "game_session_conversation_turn")
class GameSessionConversationTurnEntity {

    @Id
    UUID id;

    @Column(name = "session_id", nullable = false)
    UUID sessionId;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(nullable = false, columnDefinition = "text")
    String question;

    @Column(name = "answer_status", nullable = false)
    String answerStatus;

    @Column(name = "short_verdict", nullable = false, columnDefinition = "text")
    String shortVerdict;

    @Column(nullable = false, columnDefinition = "text")
    String explanation;

    @ElementCollection
    @CollectionTable(name = "game_session_turn_citation", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleCitation> citations = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_exception", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    @Column(name = "exception_text", nullable = false, columnDefinition = "text")
    List<String> exceptions = new ArrayList<>();

    @Column(nullable = false)
    String confidence;

    @Column(name = "answer_basis", length = 40)
    String answerBasis;

    @ElementCollection
    @CollectionTable(name = "game_session_turn_warning", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    @Column(name = "warning_type", nullable = false, length = 60)
    List<String> warnings = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_calculation", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleCalculation> calculations = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_situation_check", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleSituationCheck> situationChecks = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_walkthrough_step", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleWalkthroughStep> walkthroughSteps = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_decision_branch", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleDecisionBranch> decisionBranches = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_exception_clause", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleExceptionClause> exceptionClauses = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_term_definition", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleTermDefinition> termDefinitions = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_worked_example", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleWorkedExample> workedExamples = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_rule_priority", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRulePriorityResolution> priorityResolutions = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_rule_timing", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleTimingResolution> timingResolutions = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_rule_tie", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleTieResolution> tieResolutions = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_rule_scope", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleScopeResolution> scopeResolutions = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_concept_comparison", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleConceptComparison> conceptComparisons = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_rule_option", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleOption> ruleOptions = new ArrayList<>();

    @Column(nullable = false)
    boolean official;

    @Column(name = "confirmed_ruling_id")
    UUID confirmedRulingId;

    @Column(name = "confirmed_ruling_version")
    Long confirmedRulingVersion;

    @Column(columnDefinition = "text")
    String clarification;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected GameSessionConversationTurnEntity() {}

    GameSessionConversationTurnEntity(GameSessionConversationTurn turn) {
        StructuredRuleAnswer answer = turn.answer();
        id = turn.id();
        sessionId = turn.sessionId();
        documentVersionId = answer.documentVersionId();
        question = turn.question();
        answerStatus = answer.status().name();
        shortVerdict = answer.shortVerdict();
        explanation = answer.explanation();
        citations = answer.citations().stream()
                .map(PersistedRuleCitation::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        exceptions = new ArrayList<>(answer.exceptions());
        confidence = answer.confidence().name();
        answerBasis = answer.answerBasis() == null ? null : answer.answerBasis().name();
        warnings = answer.warnings().stream()
                .map(warning -> warning.type().name())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        calculations = answer.calculations().stream()
                .map(PersistedRuleCalculation::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        situationChecks = answer.situationChecks().stream()
                .map(PersistedRuleSituationCheck::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        walkthroughSteps = answer.walkthroughSteps().stream()
                .map(PersistedRuleWalkthroughStep::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        decisionBranches = answer.decisionBranches().stream()
                .map(PersistedRuleDecisionBranch::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        exceptionClauses = answer.exceptionClauses().stream()
                .map(PersistedRuleExceptionClause::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        termDefinitions = answer.termDefinitions().stream()
                .map(PersistedRuleTermDefinition::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        workedExamples = answer.workedExamples().stream()
                .map(PersistedRuleWorkedExample::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        priorityResolutions = answer.priorityResolutions().stream()
                .map(PersistedRulePriorityResolution::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        timingResolutions = answer.timingResolutions().stream()
                .map(PersistedRuleTimingResolution::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        tieResolutions = answer.tieResolutions().stream()
                .map(PersistedRuleTieResolution::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        scopeResolutions = answer.scopeResolutions().stream()
                .map(PersistedRuleScopeResolution::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        conceptComparisons = answer.conceptComparisons().stream()
                .map(PersistedRuleConceptComparison::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ruleOptions = answer.ruleOptions().stream()
                .map(PersistedRuleOption::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        official = answer.official();
        confirmedRulingId = answer.confirmedRulingId();
        confirmedRulingVersion = answer.confirmedRulingVersion();
        clarification = answer.clarification();
        createdBy = turn.createdBy();
        createdAt = turn.createdAt();
    }

    GameSessionConversationTurn toDomain() {
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.valueOf(answerStatus),
                shortVerdict,
                explanation,
                citations.stream().map(PersistedRuleCitation::toDomain).toList(),
                exceptions,
                AnswerConfidence.valueOf(confidence),
                answerBasis == null ? null : AnswerBasis.valueOf(answerBasis),
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification,
                warnings.stream()
                        .map(AnswerWarning.Type::valueOf)
                        .map(AnswerWarning::new)
                        .toList(),
                calculations.stream().map(PersistedRuleCalculation::toDomain).toList(),
                situationChecks.stream().map(PersistedRuleSituationCheck::toDomain).toList(),
                walkthroughSteps.stream().map(PersistedRuleWalkthroughStep::toDomain).toList(),
                decisionBranches.stream().map(PersistedRuleDecisionBranch::toDomain).toList(),
                exceptionClauses.stream().map(PersistedRuleExceptionClause::toDomain).toList(),
                termDefinitions.stream().map(PersistedRuleTermDefinition::toDomain).toList(),
                workedExamples.stream().map(PersistedRuleWorkedExample::toDomain).toList(),
                priorityResolutions.stream().map(PersistedRulePriorityResolution::toDomain).toList(),
                timingResolutions.stream().map(PersistedRuleTimingResolution::toDomain).toList(),
                tieResolutions.stream().map(PersistedRuleTieResolution::toDomain).toList(),
                scopeResolutions.stream().map(PersistedRuleScopeResolution::toDomain).toList(),
                conceptComparisons.stream().map(PersistedRuleConceptComparison::toDomain).toList(),
                ruleOptions.stream().map(PersistedRuleOption::toDomain).toList());
        return new GameSessionConversationTurn(id, sessionId, question, answer, createdBy, createdAt);
    }
}

@Embeddable
class PersistedRuleDecisionBranch {

    @Column(name = "condition_text", nullable = false, columnDefinition = "text")
    String condition;

    @Column(name = "outcome_text", nullable = false, columnDefinition = "text")
    String outcome;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleDecisionBranch() {}

    PersistedRuleDecisionBranch(RuleDecisionBranch branch) {
        condition = branch.condition();
        outcome = branch.outcome();
        basis = branch.basis().name();
        citationIds = branch.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleDecisionBranch toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleDecisionBranch(condition, outcome, DecisionBranchBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRuleExceptionClause {

    @Column(name = "condition_text", nullable = false, columnDefinition = "text")
    String condition;

    @Column(name = "effect_text", nullable = false, columnDefinition = "text")
    String effect;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleExceptionClause() {}

    PersistedRuleExceptionClause(RuleExceptionClause clause) {
        condition = clause.condition();
        effect = clause.effect();
        citationIds = clause.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleExceptionClause toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleExceptionClause(condition, effect, citations);
    }
}

@Embeddable
class PersistedRuleTermDefinition {

    @Column(name = "term_text", nullable = false, columnDefinition = "text")
    String term;

    @Column(name = "definition_text", nullable = false, columnDefinition = "text")
    String definition;

    @Column(name = "boundary_text", nullable = false, columnDefinition = "text")
    String boundary;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleTermDefinition() {}

    PersistedRuleTermDefinition(RuleTermDefinition definition) {
        term = definition.term();
        this.definition = definition.definition();
        boundary = definition.boundary();
        citationIds = definition.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleTermDefinition toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleTermDefinition(term, definition, boundary, citations);
    }
}

@Embeddable
class PersistedRuleWorkedExample {

    @Column(name = "setup_text", nullable = false, columnDefinition = "text")
    String setup;

    @Column(name = "action_text", nullable = false, columnDefinition = "text")
    String action;

    @Column(name = "outcome_text", nullable = false, columnDefinition = "text")
    String outcome;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleWorkedExample() {}

    PersistedRuleWorkedExample(RuleWorkedExample example) {
        setup = example.setup();
        action = example.action();
        outcome = example.outcome();
        basis = example.basis().name();
        citationIds = example.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleWorkedExample toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleWorkedExample(setup, action, outcome, WorkedExampleBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRulePriorityResolution {

    @Column(name = "base_rule", nullable = false, columnDefinition = "text")
    String baseRule;

    @Column(name = "competing_rule", nullable = false, columnDefinition = "text")
    String competingRule;

    @Column(name = "resolution_text", nullable = false, columnDefinition = "text")
    String resolution;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRulePriorityResolution() {}

    PersistedRulePriorityResolution(RulePriorityResolution item) {
        baseRule = item.baseRule();
        competingRule = item.competingRule();
        resolution = item.resolution();
        basis = item.basis().name();
        citationIds = item.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RulePriorityResolution toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RulePriorityResolution(
                baseRule, competingRule, resolution, RulePriorityBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRuleTimingResolution {

    @Column(name = "timing_context", nullable = false, columnDefinition = "text")
    String timingContext;

    @Column(name = "resolution_order", nullable = false, columnDefinition = "text")
    String resolutionOrder;

    @Column(name = "order_source", nullable = false, columnDefinition = "text")
    String orderSource;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleTimingResolution() {}

    PersistedRuleTimingResolution(RuleTimingResolution item) {
        timingContext = item.timingContext();
        resolutionOrder = item.resolutionOrder();
        orderSource = item.orderSource();
        basis = item.basis().name();
        citationIds = item.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleTimingResolution toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleTimingResolution(
                timingContext, resolutionOrder, orderSource, TimingOrderBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRuleTieResolution {

    @Column(name = "tie_context", nullable = false, columnDefinition = "text")
    String tieContext;

    @Column(name = "resolution_steps", nullable = false, columnDefinition = "text")
    String resolutionSteps;

    @Column(name = "final_outcome", nullable = false, columnDefinition = "text")
    String finalOutcome;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleTieResolution() {}

    PersistedRuleTieResolution(RuleTieResolution item) {
        tieContext = item.tieContext();
        resolutionSteps = encodeSteps(item.resolutionSteps());
        finalOutcome = item.finalOutcome();
        basis = item.basis().name();
        citationIds = item.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleTieResolution toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleTieResolution(
                tieContext,
                decodeSteps(resolutionSteps),
                finalOutcome,
                TieResolutionBasis.valueOf(basis),
                citations);
    }

    private static String encodeSteps(List<String> steps) {
        StringBuilder encoded = new StringBuilder("v1;");
        steps.forEach(step -> encoded.append(step.length()).append(':').append(step));
        return encoded.toString();
    }

    private static List<String> decodeSteps(String encoded) {
        if (!encoded.startsWith("v1;")) return encoded.lines().toList();
        List<String> steps = new ArrayList<>();
        int cursor = 3;
        while (cursor < encoded.length()) {
            int separator = encoded.indexOf(':', cursor);
            if (separator < 0) throw new IllegalArgumentException("persisted tie steps are invalid");
            int length = Integer.parseInt(encoded.substring(cursor, separator));
            int start = separator + 1;
            int end = start + length;
            if (length < 0 || end > encoded.length()) {
                throw new IllegalArgumentException("persisted tie steps are invalid");
            }
            steps.add(encoded.substring(start, end));
            cursor = end;
        }
        return List.copyOf(steps);
    }
}

@Embeddable
class PersistedRuleScopeResolution {

    @Column(name = "rule_context", nullable = false, columnDefinition = "text")
    String ruleContext;

    @Column(name = "governing_condition", nullable = false, columnDefinition = "text")
    String governingCondition;

    @Column(name = "current_situation", nullable = false, columnDefinition = "text")
    String currentSituation;

    @Column(name = "match_status", nullable = false, length = 40)
    String matchStatus;

    @Column(name = "effect_text", nullable = false, columnDefinition = "text")
    String effect;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleScopeResolution() {}

    PersistedRuleScopeResolution(RuleScopeResolution item) {
        ruleContext = item.ruleContext();
        governingCondition = item.governingCondition();
        currentSituation = item.currentSituation();
        matchStatus = item.matchStatus().name();
        effect = item.effect();
        basis = item.basis().name();
        citationIds = item.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleScopeResolution toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleScopeResolution(
                ruleContext, governingCondition, currentSituation, ScopeMatchStatus.valueOf(matchStatus),
                effect, ScopeBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRuleConceptComparison {

    @Column(name = "left_concept", nullable = false, columnDefinition = "text")
    String leftConcept;

    @Column(name = "left_definition", nullable = false, columnDefinition = "text")
    String leftDefinition;

    @Column(name = "right_concept", nullable = false, columnDefinition = "text")
    String rightConcept;

    @Column(name = "right_definition", nullable = false, columnDefinition = "text")
    String rightDefinition;

    @Column(name = "common_ground", nullable = false, columnDefinition = "text")
    String commonGround;

    @Column(name = "key_difference", nullable = false, columnDefinition = "text")
    String keyDifference;

    @Column(name = "practical_boundary", nullable = false, columnDefinition = "text")
    String practicalBoundary;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleConceptComparison() {}

    PersistedRuleConceptComparison(RuleConceptComparison item) {
        leftConcept = item.leftConcept();
        leftDefinition = item.leftDefinition();
        rightConcept = item.rightConcept();
        rightDefinition = item.rightDefinition();
        commonGround = item.commonGround();
        keyDifference = item.keyDifference();
        practicalBoundary = item.practicalBoundary();
        basis = item.basis().name();
        citationIds = item.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleConceptComparison toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleConceptComparison(
                leftConcept, leftDefinition, rightConcept, rightDefinition, commonGround, keyDifference,
                practicalBoundary, ConceptComparisonBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRuleOption {

    @Column(name = "decision_context", nullable = false, columnDefinition = "text")
    String decisionContext;

    @Column(name = "selection_rule", nullable = false, columnDefinition = "text")
    String selectionRule;

    @Column(name = "option_name", nullable = false, columnDefinition = "text")
    String optionName;

    @Column(name = "availability_condition", nullable = false, columnDefinition = "text")
    String availabilityCondition;

    @Column(name = "result_text", nullable = false, columnDefinition = "text")
    String result;

    @Column(nullable = false, length = 40)
    String basis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleOption() {}

    PersistedRuleOption(RuleOption item) {
        decisionContext = item.decisionContext();
        selectionRule = item.selectionRule();
        optionName = item.optionName();
        availabilityCondition = item.availabilityCondition();
        result = item.result();
        basis = item.basis().name();
        citationIds = item.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleOption toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleOption(
                decisionContext, selectionRule, optionName, availabilityCondition, result,
                RuleOptionBasis.valueOf(basis), citations);
    }
}

@Embeddable
class PersistedRuleWalkthroughStep {

    @Column(nullable = false, columnDefinition = "text")
    String instruction;

    @Column(nullable = false, columnDefinition = "text")
    String explanation;

    @Column(name = "order_basis", nullable = false, length = 40)
    String orderBasis;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleWalkthroughStep() {}

    PersistedRuleWalkthroughStep(RuleWalkthroughStep step) {
        instruction = step.instruction();
        explanation = step.explanation();
        orderBasis = step.orderBasis().name();
        citationIds = step.citationIds().stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    RuleWalkthroughStep toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleWalkthroughStep(
                instruction, explanation, WalkthroughOrderBasis.valueOf(orderBasis), citations);
    }
}

@Embeddable
class PersistedRuleSituationCheck {

    @Column(nullable = false, columnDefinition = "text")
    String requirement;

    @Column(nullable = false, length = 40)
    String status;

    @Column(name = "player_fact", nullable = false, columnDefinition = "text")
    String playerFact;

    @Column(name = "citation_ids", nullable = false, columnDefinition = "text")
    String citationIds;

    protected PersistedRuleSituationCheck() {}

    PersistedRuleSituationCheck(RuleSituationCheck check) {
        requirement = check.requirement();
        status = check.status().name();
        playerFact = check.playerFact();
        citationIds = check.citationIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
    }

    RuleSituationCheck toDomain() {
        List<UUID> citations = java.util.Arrays.stream(citationIds.split(","))
                .map(UUID::fromString)
                .toList();
        return new RuleSituationCheck(
                requirement, SituationCheckStatus.valueOf(status), playerFact, citations);
    }
}

@Embeddable
class PersistedRuleCalculation {

    @Column(nullable = false, length = 160)
    String expression;

    @Column(nullable = false, length = 80)
    String result;

    protected PersistedRuleCalculation() {}

    PersistedRuleCalculation(RuleCalculation calculation) {
        expression = calculation.expression();
        result = calculation.result();
    }

    RuleCalculation toDomain() {
        return new RuleCalculation(expression, result);
    }
}

@Embeddable
class PersistedRuleCitation {

    @Column(name = "chunk_id", nullable = false)
    UUID chunkId;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "section_type", nullable = false)
    String sectionType;

    @Column(nullable = false, columnDefinition = "text")
    String heading;

    @Column(nullable = false, columnDefinition = "text")
    String excerpt;

    @Column(name = "page_from", nullable = false)
    int pageFrom;

    @Column(name = "page_to", nullable = false)
    int pageTo;

    protected PersistedRuleCitation() {}

    PersistedRuleCitation(RuleCitation citation) {
        chunkId = citation.chunkId();
        documentVersionId = citation.documentVersionId();
        sectionType = citation.sectionType();
        heading = citation.heading();
        excerpt = citation.excerpt();
        pageFrom = citation.pageFrom();
        pageTo = citation.pageTo();
    }

    RuleCitation toDomain() {
        return new RuleCitation(
                chunkId, documentVersionId, sectionType, heading, excerpt, pageFrom, pageTo);
    }
}
