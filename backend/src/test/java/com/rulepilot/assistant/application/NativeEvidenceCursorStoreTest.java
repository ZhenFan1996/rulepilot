package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeEvidenceCursorStoreTest {

    private static final String TOOL_NAME = "search_rule_evidence";
    private static final String FIRST_FINGERPRINT = "first-request";
    private static final String SECOND_FINGERPRINT = "second-request";

    @Test
    void sameToolQueriesKeepIndependentCursorsWhenContinuedInterleaved() {
        NativeEvidenceCursorStore cursors = new NativeEvidenceCursorStore();
        ToolScope scope = scope();
        NativeEvidenceCursorStore.Position firstPosition = position(1);
        NativeEvidenceCursorStore.Position secondPosition = position(10);

        String firstCursor = cursors.continueFrom(scope, TOOL_NAME, FIRST_FINGERPRINT, null, firstPosition);
        String secondCursor = cursors.continueFrom(scope, TOOL_NAME, SECOND_FINGERPRINT, null, secondPosition);

        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor, position(99)))
                .isEqualTo(firstPosition);
        assertThat(cursors.open(scope, TOOL_NAME, SECOND_FINGERPRINT, secondCursor, position(99)))
                .isEqualTo(secondPosition);

        NativeEvidenceCursorStore.Position continuedFirstPosition = position(2);
        NativeEvidenceCursorStore.Position continuedSecondPosition = position(11);
        String continuedFirstCursor = cursors.continueFrom(
                scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor, continuedFirstPosition);
        String continuedSecondCursor = cursors.continueFrom(
                scope, TOOL_NAME, SECOND_FINGERPRINT, secondCursor, continuedSecondPosition);

        assertThat(continuedFirstCursor).isNotEqualTo(firstCursor).isNotEqualTo(continuedSecondCursor);
        assertThat(continuedSecondCursor).isNotEqualTo(secondCursor);
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, continuedFirstCursor, position(99)))
                .isEqualTo(continuedFirstPosition);
        assertThat(cursors.open(scope, TOOL_NAME, SECOND_FINGERPRINT, continuedSecondCursor, position(99)))
                .isEqualTo(continuedSecondPosition);
    }

    @Test
    void forgedOrMismatchedCursorsAreRejectedWithoutConsumingTheValidCursor() {
        NativeEvidenceCursorStore cursors = new NativeEvidenceCursorStore();
        ToolScope scope = scope();
        NativeEvidenceCursorStore.Position savedPosition = position(3);
        String cursor = cursors.continueFrom(scope, TOOL_NAME, FIRST_FINGERPRINT, null, savedPosition);

        assertRejected(cursors, scope, TOOL_NAME, FIRST_FINGERPRINT, UUID.randomUUID().toString());
        assertRejected(cursors, scope, TOOL_NAME, SECOND_FINGERPRINT, cursor);
        assertRejected(cursors, scope, "read_rule_pages", FIRST_FINGERPRINT, cursor);
        assertRejected(
                cursors,
                new ToolScope(
                        scope.ownerUsername(),
                        UUID.randomUUID(),
                        scope.runId(),
                        scope.deadlineAt(),
                        scope.maxObservationTokens()),
                TOOL_NAME,
                FIRST_FINGERPRINT,
                cursor);
        assertRejected(
                cursors,
                new ToolScope(
                        scope.ownerUsername(),
                        scope.documentVersionId(),
                        UUID.randomUUID(),
                        scope.deadlineAt(),
                        scope.maxObservationTokens()),
                TOOL_NAME,
                FIRST_FINGERPRINT,
                cursor);
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, cursor, position(99)))
                .isEqualTo(savedPosition);
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, cursor, position(99)))
                .isEqualTo(savedPosition);
    }

    @Test
    void successfulContinuationRotatesOnlyTheConsumedCursor() {
        NativeEvidenceCursorStore cursors = new NativeEvidenceCursorStore();
        ToolScope scope = scope();
        String firstCursor = cursors.continueFrom(scope, TOOL_NAME, FIRST_FINGERPRINT, null, position(1));
        String otherCursor = cursors.continueFrom(scope, TOOL_NAME, SECOND_FINGERPRINT, null, position(10));

        assertThatThrownBy(() -> cursors.continueFrom(
                        scope, TOOL_NAME, SECOND_FINGERPRINT, firstCursor, position(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor, position(99)))
                .isEqualTo(position(1));

        String nextCursor = cursors.continueFrom(scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor, position(2));

        assertRejected(cursors, scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor);
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, nextCursor, position(99)))
                .isEqualTo(position(2));
        assertThat(cursors.open(scope, TOOL_NAME, SECOND_FINGERPRINT, otherCursor, position(99)))
                .isEqualTo(position(10));
    }

    @Test
    void closeRemovesOnlyTheRequestedCursorOrAllMatchingQueryCursors() {
        NativeEvidenceCursorStore cursors = new NativeEvidenceCursorStore();
        ToolScope scope = scope();
        String firstCursor = cursors.continueFrom(scope, TOOL_NAME, FIRST_FINGERPRINT, null, position(1));
        String secondCursor = cursors.continueFrom(scope, TOOL_NAME, FIRST_FINGERPRINT, null, position(2));
        String otherCursor = cursors.continueFrom(scope, TOOL_NAME, SECOND_FINGERPRINT, null, position(10));

        assertThatThrownBy(() -> cursors.close(scope, TOOL_NAME, SECOND_FINGERPRINT, firstCursor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor, position(99)))
                .isEqualTo(position(1));

        cursors.close(scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor);

        assertRejected(cursors, scope, TOOL_NAME, FIRST_FINGERPRINT, firstCursor);
        assertThat(cursors.open(scope, TOOL_NAME, FIRST_FINGERPRINT, secondCursor, position(99)))
                .isEqualTo(position(2));

        cursors.close(scope, TOOL_NAME, FIRST_FINGERPRINT, null);

        assertRejected(cursors, scope, TOOL_NAME, FIRST_FINGERPRINT, secondCursor);
        assertThat(cursors.open(scope, TOOL_NAME, SECOND_FINGERPRINT, otherCursor, position(99)))
                .isEqualTo(position(10));
    }

    @Test
    void expiredCursorIsRejectedAndPurged() {
        NativeEvidenceCursorStore cursors = new NativeEvidenceCursorStore();
        ToolScope expiredScope = new ToolScope(
                "player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(1));
        String expiredCursor =
                cursors.continueFrom(expiredScope, TOOL_NAME, FIRST_FINGERPRINT, null, position(1));

        assertRejected(cursors, expiredScope, TOOL_NAME, FIRST_FINGERPRINT, expiredCursor);
    }

    private static void assertRejected(
            NativeEvidenceCursorStore cursors,
            ToolScope scope,
            String toolName,
            String fingerprint,
            String cursor) {
        assertThatThrownBy(() -> cursors.open(scope, toolName, fingerprint, cursor, position(99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    private static ToolScope scope() {
        return new ToolScope(
                "player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30));
    }

    private static NativeEvidenceCursorStore.Position position(int primaryOffset) {
        return new NativeEvidenceCursorStore.Position(primaryOffset, 0, 0, List.of());
    }
}
