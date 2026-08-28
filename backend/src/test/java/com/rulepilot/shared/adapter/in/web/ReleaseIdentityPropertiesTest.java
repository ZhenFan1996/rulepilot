package com.rulepilot.shared.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReleaseIdentityPropertiesTest {

    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void derivesTheCommitShaFromCurrentAndHistoricalDeploymentReleaseIds() {
        assertThat(new ReleaseIdentityProperties(COMMIT_SHA + "-12345-2").commitSha())
                .isEqualTo(COMMIT_SHA);
        assertThat(new ReleaseIdentityProperties(COMMIT_SHA + "-12345").commitSha())
                .isEqualTo(COMMIT_SHA);
    }

    @Test
    void retainsTheExplicitLocalDevelopmentIdentity() {
        var identity = new ReleaseIdentityProperties("local");

        assertThat(identity.id()).isEqualTo("local");
        assertThat(identity.commitSha()).isEqualTo("local");
    }

    @ParameterizedTest
    @MethodSource("invalidReleaseIds")
    void rejectsReleaseIdsThatCannotProveAnExactLowercaseCommit(String releaseId) {
        assertThatThrownBy(() -> new ReleaseIdentityProperties(releaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RulePilot release id is invalid");
    }

    private static Stream<String> invalidReleaseIds() {
        return Stream.of(
                null,
                "",
                " local",
                "LOCAL",
                "0123456789abcdef",
                COMMIT_SHA,
                COMMIT_SHA.toUpperCase() + "-12345-2",
                COMMIT_SHA + "-run-2",
                COMMIT_SHA + "-12345-attempt",
                COMMIT_SHA + "-12345-2-extra");
    }
}
