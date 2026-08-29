package com.rulepilot.assistant.application;

import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maintains complete native call/result continuity without treating prior model prose as evidence. */
final class NativeAgentConversation {

    private final ConversationMessage system;
    private final ConversationMessage player;
    private final List<Exchange> exchanges = new ArrayList<>();
    private Exchange current;

    NativeAgentConversation(String systemPrompt, String playerRequest) {
        if (systemPrompt == null || systemPrompt.isBlank() || playerRequest == null || playerRequest.isBlank()) {
            throw new IllegalArgumentException("native Agent conversation context is invalid");
        }
        this.system = ConversationMessage.system(systemPrompt.strip());
        this.player = ConversationMessage.user(playerRequest.strip());
    }

    List<ConversationMessage> messages() {
        if (current != null && !current.complete()) {
            throw new IllegalStateException("native tool call/result continuity is incomplete");
        }
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(player);
        exchanges.forEach(exchange -> messages.addAll(exchange.messages));
        List<ConversationMessage> nextTurn = List.copyOf(messages);
        exchanges.forEach(Exchange::consumeMedia);
        return nextTurn;
    }

    void appendAssistant(String text, List<ModelToolCall> calls, List<ToolSpec> advertisedTools) {
        if (current != null && !current.complete()) {
            throw new IllegalStateException("previous native tool calls are unresolved");
        }
        Map<String, ToolSpec> advertised = new LinkedHashMap<>();
        advertisedTools.forEach(spec -> advertised.put(spec.name(), spec));
        current = new Exchange(ConversationMessage.assistant(text, calls), calls, advertised);
        exchanges.add(current);
    }

    void assertAdvertisedSchema(ModelToolCall call, ToolSpec currentSpec) {
        if (current == null || current.complete() || current.nextCall() == null
                || !current.nextCall().id().equals(call.id()) || !current.nextCall().name().equals(call.name())) {
            throw new IllegalStateException("native tool call order is inconsistent");
        }
        ToolSpec advertised = current.advertised.get(call.name());
        if (advertised == null || !advertised.schemaHash().equals(currentSpec.schemaHash())) {
            throw new StaleSchemaException();
        }
    }

    void appendTool(ModelToolCall call, String observationJson) {
        current.messages.add(ConversationMessage.tool(call.id(), call.name(), observationJson));
        current.resolvedCalls++;
    }

    void appendVisual(String content, List<com.rulepilot.assistant.NativeAgentTool.ToolMedia> media) {
        if (current == null || current.resolvedCalls == 0) {
            throw new IllegalStateException("visual observation has no correlated tool result");
        }
        current.messages.add(ConversationMessage.visualObservation(content, media));
    }

    void appendApplicationInstruction(String content) {
        if (content == null || content.isBlank() || current == null || !current.complete()) {
            throw new IllegalStateException("native Agent instruction boundary is invalid");
        }
        current = Exchange.instruction(ConversationMessage.user(content.strip()));
        exchanges.add(current);
    }

    static final class StaleSchemaException extends RuntimeException {}

    private static final class Exchange {
        private final List<ConversationMessage> messages = new ArrayList<>();
        private final List<ModelToolCall> calls;
        private final Map<String, ToolSpec> advertised;
        private int resolvedCalls;

        private Exchange(
                ConversationMessage assistant, List<ModelToolCall> calls, Map<String, ToolSpec> advertised) {
            this.messages.add(assistant);
            this.calls = List.copyOf(calls);
            this.advertised = Map.copyOf(advertised);
        }

        private static Exchange instruction(ConversationMessage instruction) {
            return new Exchange(instruction, List.of(), Map.of());
        }

        private ModelToolCall nextCall() {
            return resolvedCalls >= calls.size() ? null : calls.get(resolvedCalls);
        }

        private boolean complete() {
            return resolvedCalls == calls.size();
        }

        /** Media is a one-turn observation; its textual provenance remains in the durable conversation. */
        private void consumeMedia() {
            for (int index = 0; index < messages.size(); index++) {
                ConversationMessage message = messages.get(index);
                if (!message.media().isEmpty()) {
                    messages.set(index, ConversationMessage.user(message.content()));
                }
            }
        }
    }
}
