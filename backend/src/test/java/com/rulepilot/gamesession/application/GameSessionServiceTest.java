package com.rulepilot.gamesession.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.gamesession.domain.GameSession;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameSessionServiceTest {

    private final UUID gameId = UUID.randomUUID();
    private final UUID editionId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID expansionId = UUID.randomUUID();

    @Test
    void startsAndUpdatesOwnedReadySession() {
        InMemorySessions repository = new InMemorySessions();
        InMemoryContexts contexts = new InMemoryContexts();
        GameSessionService service = service(repository, contexts, Set.of(expansionId), editionId, "READY");

        GameSession started = service.start(
                editionId, versionId, Set.of(expansionId), 4, "SETUP", 1, "alice");
        GameSession updated = service.updateTurn(started.id(), 2, "ACTION", 3, "alice");

        assertThat(started.gameId()).isEqualTo(gameId);
        assertThat(started.roundNumber()).isEqualTo(1);
        assertThat(updated.roundNumber()).isEqualTo(2);
        assertThat(updated.phase()).isEqualTo("ACTION");
        assertThat(updated.activePlayer()).isEqualTo(3);
        assertThat(service.get(started.id(), "alice")).isEqualTo(updated);
        assertThat(contexts.value).isEqualTo(updated);
    }

    @Test
    void restoresExpiredContextFromDatabase() {
        InMemorySessions repository = new InMemorySessions();
        InMemoryContexts contexts = new InMemoryContexts();
        GameSessionService service = service(repository, contexts, Set.of(), editionId, "READY");
        GameSession started = service.start(editionId, versionId, Set.of(), 2, "SETUP", 1, "alice");
        contexts.value = null;

        GameSession restored = service.get(started.id(), "alice");

        assertThat(restored).isEqualTo(started);
        assertThat(contexts.value).isEqualTo(started);
    }

    @Test
    void startsFromTheDocumentScopeWithoutMakingThePlayerChooseAnEditionAgain() {
        InMemorySessions repository = new InMemorySessions();
        GameSessionService service = service(repository, new InMemoryContexts(), Set.of(), editionId, "READY");

        GameSession started = service.startFromDocument(
                versionId, Set.of(), 3, "开局准备", 1, "alice");

        assertThat(started.editionId()).isEqualTo(editionId);
        assertThat(started.documentVersionId()).isEqualTo(versionId);
        assertThat(started.playerCount()).isEqualTo(3);
    }

    @Test
    void continuesFromPostgreSqlWhenContextStoreIsUnavailable() {
        InMemorySessions repository = new InMemorySessions();
        GameSessionService service = service(
                repository, new UnavailableContexts(), Set.of(), editionId, "READY");

        GameSession started = service.start(editionId, versionId, Set.of(), 2, "SETUP", 1, "alice");
        GameSession restored = service.get(started.id(), "alice");
        GameSession updated = service.updateTurn(started.id(), 2, "ACTION", 2, "alice");

        assertThat(restored).isEqualTo(started);
        assertThat(updated.roundNumber()).isEqualTo(2);
        assertThat(repository.value).isEqualTo(updated);
    }

    @Test
    void rejectsIncompatibleExpansionAndDocumentScope() {
        InMemorySessions repository = new InMemorySessions();
        InMemoryContexts contexts = new InMemoryContexts();
        GameSessionService noExpansions = service(repository, contexts, Set.of(), editionId, "READY");

        assertThatThrownBy(() -> noExpansions.start(
                        editionId, versionId, Set.of(expansionId), 4, "SETUP", 1, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatible");

        GameSessionService wrongEdition =
                service(repository, contexts, Set.of(expansionId), UUID.randomUUID(), "READY");
        assertThatThrownBy(() -> wrongEdition.start(
                        editionId, versionId, Set.of(), 4, "SETUP", 1, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    private GameSessionService service(
            GameSessionRepository repository,
            GameSessionContextStore contexts,
            Set<UUID> compatibleExpansions,
            UUID documentEditionId,
            String processingStatus) {
        CatalogEditionLookup catalog = id -> Optional.of(
                new CatalogEditionLookup.EditionReference(
                        editionId, gameId, "Base", "en", compatibleExpansions));
        DocumentVersionScopeLookup documents = id -> Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, documentEditionId, processingStatus));
        return new GameSessionService(catalog, documents, repository, contexts);
    }

    private static final class InMemorySessions implements GameSessionRepository {
        private GameSession value;

        @Override
        public GameSession save(GameSession session) {
            value = session;
            return session;
        }

        @Override
        public Optional<GameSession> find(UUID sessionId) {
            return value != null && value.id().equals(sessionId) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public void update(GameSession session) {
            value = session;
        }
    }

    private static final class InMemoryContexts implements GameSessionContextStore {
        private GameSession value;

        @Override
        public void save(GameSession session) {
            value = session;
        }

        @Override
        public Optional<GameSession> find(UUID sessionId) {
            return value != null && value.id().equals(sessionId) ? Optional.of(value) : Optional.empty();
        }
    }

    private static final class UnavailableContexts implements GameSessionContextStore {
        @Override
        public void save(GameSession session) {
            throw new IllegalStateException("Redis unavailable");
        }

        @Override
        public Optional<GameSession> find(UUID sessionId) {
            throw new IllegalStateException("Redis unavailable");
        }
    }
}
