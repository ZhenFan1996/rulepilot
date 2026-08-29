package com.rulepilot.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import com.rulepilot.assistant.NativeToolScopeAuthorizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class NativeAgentToolRegistry {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{2,63}");

    private final Map<String, RegisteredTool> tools;
    private final NativeToolScopeAuthorizer scopeAuthorizer;

    public NativeAgentToolRegistry(
            List<NativeAgentTool> tools, ObjectMapper objectMapper, NativeToolScopeAuthorizer scopeAuthorizer) {
        if (tools == null || objectMapper == null || scopeAuthorizer == null) {
            throw new IllegalArgumentException("native tool registry is invalid");
        }
        Map<String, RegisteredTool> registered = new LinkedHashMap<>();
        for (NativeAgentTool tool : tools) {
            validate(tool, objectMapper);
            ToolSpec spec = new ToolSpec(
                    tool.name(), tool.description(), tool.inputSchema(), tool.schemaVersion(), schemaHash(tool));
            if (registered.putIfAbsent(tool.name(), new RegisteredTool(tool, spec)) != null) {
                throw new IllegalArgumentException("duplicate native tool name: " + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
        this.scopeAuthorizer = scopeAuthorizer;
    }

    public List<ToolSpec> specifications(Role role, Set<String> allowedNames) {
        if (role == null) throw new IllegalArgumentException("native tool role is required");
        if (allowedNames == null || allowedNames.isEmpty()) {
            throw new IllegalArgumentException("native tool allow-list is required");
        }
        return tools.values().stream()
                .filter(tool -> tool.tool().allowedRoles().contains(role))
                .filter(tool -> allowedNames.contains(tool.spec().name()))
                .map(RegisteredTool::spec)
                .sorted(Comparator.comparing(ToolSpec::name))
                .toList();
    }

    public ToolExecution execute(Role role, String name, String argumentsJson, ToolScope scope) {
        RegisteredTool registered = tools.get(name);
        if (registered == null || role == null || !registered.tool().allowedRoles().contains(role)) {
            return new ToolExecution(unknownSpec(name), ToolObservation.error("TOOL_NOT_ALLOWED"));
        }
        if (!scopeAuthorizer.isAuthorized(scope)) {
            return new ToolExecution(registered.spec(), ToolObservation.error("SCOPE_REJECTED"));
        }
        if (argumentsJson == null || argumentsJson.isBlank() || scope == null) {
            return invalidArguments(registered, "argumentsJson must contain one JSON object");
        }
        try {
            return new ToolExecution(registered.spec(), registered.tool().execute(argumentsJson, scope));
        } catch (IllegalArgumentException exception) {
            return invalidArguments(registered, validationError(exception));
        } catch (RuntimeException exception) {
            return new ToolExecution(registered.spec(), ToolObservation.error("TOOL_EXECUTION_FAILED"));
        }
    }

    private ToolExecution invalidArguments(RegisteredTool registered, String validationError) {
        return new ToolExecution(
                registered.spec(),
                new ToolObservation(
                        NativeAgentTool.ObservationStatus.ERROR,
                        "INVALID_ARGUMENT",
                        Map.of(
                                "validationError", validationError,
                                "inputSchema", registered.spec().inputSchema(),
                                "schemaHash", registered.spec().schemaHash(),
                                "allowedToolName", registered.spec().name()),
                        0));
    }

    private String validationError(IllegalArgumentException exception) {
        String summary = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Arguments did not satisfy the advertised tool contract"
                : exception.getMessage();
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException jsonFailure) {
                String location = jsonFailure.getLocation() == null
                        ? ""
                        : " at line " + jsonFailure.getLocation().getLineNr()
                                + ", column " + jsonFailure.getLocation().getColumnNr();
                // Parser messages may echo an invalid token from the arguments. The tool-level reason and location
                // are enough for the same Agent to repair its typed action without reflecting sensitive input.
                return summary + location;
            }
            cause = cause.getCause();
        }
        return summary;
    }

    public ToolSpec specification(Role role, String name) {
        RegisteredTool registered = tools.get(name);
        return registered != null && role != null && registered.tool().allowedRoles().contains(role)
                ? registered.spec()
                : unknownSpec(name);
    }

    private void validate(NativeAgentTool tool, ObjectMapper objectMapper) {
        if (tool == null || !tool.readOnly() || !NAME.matcher(tool.name()).matches()
                || tool.description() == null || tool.description().isBlank()
                || tool.schemaVersion() == null || tool.schemaVersion().isBlank()
                || tool.allowedRoles() == null || tool.allowedRoles().isEmpty()) {
            throw new IllegalArgumentException("native tool definition is invalid");
        }
        try {
            if (!objectMapper.readTree(tool.inputSchema()).isObject()) {
                throw new IllegalArgumentException("native tool input schema must be a JSON object");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("native tool input schema is invalid JSON", exception);
        }
    }

    private String schemaHash(NativeAgentTool tool) {
        String identity = tool.name() + "\n" + tool.description() + "\n" + tool.schemaVersion() + "\n" + tool.inputSchema();
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ToolSpec unknownSpec(String name) {
        String safeName = name != null && NAME.matcher(name).matches() ? name : "unknown_tool";
        return new ToolSpec(safeName, "Unregistered tool", "{\"type\":\"object\"}", "unknown", "unknown");
    }

    private record RegisteredTool(NativeAgentTool tool, ToolSpec spec) {}

    public record ToolExecution(ToolSpec specification, ToolObservation observation) {
        public ToolExecution {
            if (specification == null || observation == null) {
                throw new IllegalArgumentException("native tool execution is invalid");
            }
        }
    }
}
