package com.rulepilot.assistant.adapter.out.persistence;

import com.rulepilot.assistant.application.GameSessionConversationRepository;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
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

    @Column(nullable = false, length = 800)
    String question;

    @Column(name = "answer_status", nullable = false)
    String answerStatus;

    @Column(name = "short_verdict", nullable = false, length = 240)
    String shortVerdict;

    @Column(nullable = false, length = 1500)
    String explanation;

    @ElementCollection
    @CollectionTable(name = "game_session_turn_citation", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    List<PersistedRuleCitation> citations = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "game_session_turn_exception", joinColumns = @JoinColumn(name = "turn_id"))
    @OrderColumn(name = "position")
    @Column(name = "exception_text", nullable = false, length = 400)
    List<String> exceptions = new ArrayList<>();

    @Column(nullable = false)
    String confidence;

    @Column(name = "answer_basis", length = 40)
    String answerBasis;

    @Column(nullable = false)
    boolean official;

    @Column(name = "confirmed_ruling_id")
    UUID confirmedRulingId;

    @Column(name = "confirmed_ruling_version")
    Long confirmedRulingVersion;

    @Column(length = 800)
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
                clarification);
        return new GameSessionConversationTurn(id, sessionId, question, answer, createdBy, createdAt);
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

    @Column(nullable = false, length = 300)
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
