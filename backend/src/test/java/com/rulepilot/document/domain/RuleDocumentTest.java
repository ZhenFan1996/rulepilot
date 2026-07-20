package com.rulepilot.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleDocumentTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");

    @Test
    void canBeCreatedWithoutAGameEditionAndAssignedLater() {
        RuleDocument document = RuleDocument.create(null, "SETI Rules", DocumentSourceType.BASE_RULEBOOK, "alice", NOW);
        UUID editionId = UUID.randomUUID();

        RuleDocument assigned = document.assignTo(editionId);

        assertThat(document.gameEditionId()).isNull();
        assertThat(assigned.gameEditionId()).isEqualTo(editionId);
        assertThat(assigned.id()).isEqualTo(document.id());
    }

    @Test
    void assigningTheSameEditionIsIdempotent() {
        UUID editionId = UUID.randomUUID();
        RuleDocument document = RuleDocument.create(
                editionId, "SETI Rules", DocumentSourceType.BASE_RULEBOOK, "alice", NOW);

        assertThat(document.assignTo(editionId)).isSameAs(document);
    }

    @Test
    void cannotSilentlyMoveAnAssignedRulebook() {
        RuleDocument document = RuleDocument.create(
                UUID.randomUUID(), "SETI Rules", DocumentSourceType.BASE_RULEBOOK, "alice", NOW);

        assertThatThrownBy(() -> document.assignTo(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another game edition");
    }
}
