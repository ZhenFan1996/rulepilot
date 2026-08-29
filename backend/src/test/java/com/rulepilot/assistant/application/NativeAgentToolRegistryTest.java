package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NativeAgentToolRegistryTest {

    @Test
    void filtersByRoleAndBindsExecutionToHiddenApplicationScope() {
        AtomicReference<ToolScope> receivedScope = new AtomicReference<>();
        NativeAgentTool answerTool = tool("search_rule_evidence", Set.of(Role.ANSWER), receivedScope);
        NativeAgentToolRegistry registry = registry(List.of(answerTool));
        ToolScope scope = scope();

        assertThat(registry.specifications(Role.ANSWER, Set.of("search_rule_evidence")))
                .singleElement()
                .satisfies(spec -> {
            assertThat(spec.name()).isEqualTo("search_rule_evidence");
            assertThat(spec.schemaVersion()).isEqualTo("1");
            assertThat(spec.schemaHash()).matches("[a-f0-9]{64}");
            assertThat(spec.inputSchema()).doesNotContain("documentVersionId", "ownerUsername");
        });
        assertThat(registry.specifications(Role.TEACHING, Set.of("search_rule_evidence"))).isEmpty();

        var execution = registry.execute(Role.ANSWER, "search_rule_evidence", "{\"query\":\"setup\"}", scope);

        assertThat(execution.observation().status()).isEqualTo(NativeAgentTool.ObservationStatus.SUCCESS);
        assertThat(receivedScope.get()).isEqualTo(scope);
    }

    @Test
    void rejectsUnknownOrWrongRoleToolsWithoutExecutingThem() {
        AtomicReference<ToolScope> receivedScope = new AtomicReference<>();
        NativeAgentToolRegistry registry = registry(List.of(
                tool("search_rule_evidence", Set.of(Role.ANSWER), receivedScope)));

        assertThat(registry.execute(Role.TEACHING, "search_rule_evidence", "{}", scope()).observation().code())
                .isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(registry.execute(Role.ANSWER, "write_file", "{}", scope()).observation().code())
                .isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(receivedScope.get()).isNull();
    }

    @Test
    void requiresEveryAgentTurnToDeclareItsToolPortfolio() {
        NativeAgentToolRegistry registry = registry(List.of(
                tool("search_rule_evidence", Set.of(Role.ANSWER), new AtomicReference<>())));

        assertThatThrownBy(() -> registry.specifications(Role.ANSWER, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow-list");
    }

    @Test
    void rejectsMutableDuplicateOrInvalidSchemaDefinitionsAtStartup() {
        NativeAgentTool valid = tool("search_rule_evidence", Set.of(Role.ANSWER), new AtomicReference<>());
        assertThatThrownBy(() -> registry(List.of(valid, valid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        NativeAgentTool mutable = new DelegatingTool(valid) {
            @Override
            public boolean readOnly() {
                return false;
            }
        };
        assertThatThrownBy(() -> registry(List.of(mutable)))
                .isInstanceOf(IllegalArgumentException.class);

        NativeAgentTool invalidSchema = new DelegatingTool(valid) {
            @Override
            public String inputSchema() {
                return "not-json";
            }
        };
        assertThatThrownBy(() -> registry(List.of(invalidSchema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void rejectsCrossOwnerOrUnreadyScopeBeforeToolExecution() {
        AtomicReference<ToolScope> receivedScope = new AtomicReference<>();
        NativeAgentTool tool = tool("search_rule_evidence", Set.of(Role.ANSWER), receivedScope);
        NativeAgentToolRegistry registry = new NativeAgentToolRegistry(
                List.of(tool), JsonMapper.builder().build(), ignored -> false);

        var result = registry.execute(Role.ANSWER, tool.name(), "{}", scope());

        assertThat(result.observation().code()).isEqualTo("SCOPE_REJECTED");
        assertThat(receivedScope.get()).isNull();
    }

    @Test
    void returnsTheExactArgumentErrorAndCurrentSchemaToTheSameAgent() {
        NativeAgentTool valid = tool("search_rule_evidence", Set.of(Role.ANSWER), new AtomicReference<>());
        NativeAgentTool rejecting = new DelegatingTool(valid) {
            @Override
            public ToolObservation execute(String input, ToolScope scope) {
                throw new IllegalArgumentException("limit must be a positive requested candidate count");
            }
        };
        NativeAgentToolRegistry registry = registry(List.of(rejecting));

        var result = registry.execute(Role.ANSWER, rejecting.name(), "{\"limit\":0}", scope());

        assertThat(result.observation().code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.observation().data())
                .containsEntry("validationError", "limit must be a positive requested candidate count")
                .containsEntry("inputSchema", rejecting.inputSchema())
                .containsEntry("schemaHash", result.specification().schemaHash())
                .containsEntry("allowedToolName", rejecting.name());
        assertThat(result.observation().data().toString()).doesNotContain("{\"limit\":0}");

        NativeAgentTool search = new SearchRuleEvidenceNativeTool(
                request -> List.of(), JsonMapper.builder().build());
        var malformed = registry(List.of(search)).execute(
                Role.ANSWER, search.name(), "{\"query\":", scope());
        assertThat(malformed.observation().data().get("validationError").toString())
                .contains("search arguments JSON could not be decoded", "line 1", "column");
        assertThat(malformed.observation().data().get("validationError").toString())
                .doesNotContain("Unexpected end-of-input", "query");

        var blank = registry(List.of(search)).execute(Role.ANSWER, search.name(), " ", scope());
        assertThat(blank.observation().data())
                .containsEntry("validationError", "argumentsJson must contain one JSON object")
                .containsEntry("inputSchema", search.inputSchema())
                .containsEntry("allowedToolName", search.name());
    }

    private NativeAgentToolRegistry registry(List<NativeAgentTool> tools) {
        return new NativeAgentToolRegistry(tools, JsonMapper.builder().build(), ignored -> true);
    }

    private NativeAgentTool tool(String name, Set<Role> roles, AtomicReference<ToolScope> receivedScope) {
        return new NativeAgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Read evidence";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}";
            }

            @Override
            public String schemaVersion() {
                return "1";
            }

            @Override
            public Set<Role> allowedRoles() {
                return roles;
            }

            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                receivedScope.set(scope);
                return ToolObservation.success("FOUND", Map.of("result", "bounded"), 1);
            }
        };
    }

    private ToolScope scope() {
        return new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30));
    }

    private abstract static class DelegatingTool implements NativeAgentTool {
        private final NativeAgentTool delegate;

        private DelegatingTool(NativeAgentTool delegate) {
            this.delegate = delegate;
        }

        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public String inputSchema() { return delegate.inputSchema(); }
        @Override public String schemaVersion() { return delegate.schemaVersion(); }
        @Override public Set<Role> allowedRoles() { return delegate.allowedRoles(); }
        @Override public ToolObservation execute(String input, ToolScope scope) { return delegate.execute(input, scope); }
    }
}
