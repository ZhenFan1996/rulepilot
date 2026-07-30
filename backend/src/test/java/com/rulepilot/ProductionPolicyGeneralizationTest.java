package com.rulepilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProductionPolicyGeneralizationTest {

    private static final List<Path> POLICY_ROOTS = List.of(
            Path.of("src/main/java/com/rulepilot/assistant/application"),
            Path.of("src/main/java/com/rulepilot/teaching/application"));
    private static final List<String> CORPUS_LOCAL_TERMS = List.of(
            "master builder",
            "emotion card",
            "品质瓷砖",
            "品质板",
            "matching_value_resolution",
            "exhausted_source",
            "end_turn_procedure",
            "state_transition",
            "round_reset",
            "deferred_turn",
            "answerreplenishmentpolicy",
            "evidenced_successor_rule",
            "resolving a bump",
            "collision bump priority");

    @Test
    void productionDecisionPoliciesDoNotNameKnownCorpusLocalMechanics() throws IOException {
        for (Path root : POLICY_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(source).toLowerCase(Locale.ROOT);
                    for (String forbidden : CORPUS_LOCAL_TERMS) {
                        assertThat(content)
                                .as("%s must derive terminology from the active rulebook instead of naming '%s'", source, forbidden)
                                .doesNotContain(forbidden);
                    }
                }
            }
        }
    }

    @Test
    void runtimePromptAssemblyDoesNotLoadTheRetiredCorpusSpecificRevision() throws IOException {
        String source = Files.readString(Path.of(
                        "src/main/java/com/rulepilot/modelconfig/VersionedAgentPrompts.java"))
                .toLowerCase(Locale.ROOT);

        assertThat(source).doesNotContain("rule-answer-agent-v15-matching-value-resolution-system.txt");
    }
}
