package com.rulepilot.agenttrace;

import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import java.util.Optional;
import java.util.UUID;

public interface CaptureHandle {

    boolean enabled();

    Optional<UUID> traceId();

    void userTurn(UserTurn event);

    void modelCallStarted(ModelCallStarted event);

    void modelTurn(ModelTurn event);

    void toolCall(ToolCall event);

    void toolObservation(ToolObservation event);

    void publication(Publication event);

    void bindingOrFailure(BindingOrFailure event);

    boolean bind(ResourceRef resource);

    static CaptureHandle noop() {
        return NoopCaptureHandle.INSTANCE;
    }

    enum NoopCaptureHandle implements CaptureHandle {
        INSTANCE;

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public Optional<UUID> traceId() {
            return Optional.empty();
        }

        @Override
        public void userTurn(UserTurn event) {}

        @Override
        public void modelCallStarted(ModelCallStarted event) {}

        @Override
        public void modelTurn(ModelTurn event) {}

        @Override
        public void toolCall(ToolCall event) {}

        @Override
        public void toolObservation(ToolObservation event) {}

        @Override
        public void publication(Publication event) {}

        @Override
        public void bindingOrFailure(BindingOrFailure event) {}

        @Override
        public boolean bind(ResourceRef resource) {
            return false;
        }
    }
}
