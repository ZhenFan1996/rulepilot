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

        assertThat(registry.specifications(Role.ANSWER)).singleElement().satisfies(spec -> {
            assertThat(spec.name()).isEqualTo("search_rule_evidence");
            assertThat(spec.schemaVersion()).isEqualTo("1");
            assertThat(spec.schemaHash()).matches("[a-f0-9]{64}");
            assertThat(spec.inputSchema()).doesNotContain("documentVersionId", "ownerUsername");
        });
        assertThat(registry.specifications(Role.TEACHING)).isEmpty();

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
