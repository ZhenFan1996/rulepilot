package com.rulepilot.assistant;

import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/** Explicit, fail-open bridge from assistant workflows to the optional private trace module. */
public final class PrivateAgentTraceCapture {

    private PrivateAgentTraceCapture() {}

    public static CaptureHandle current(
            Optional<PrivateAgentTraceService> traces, Principal principal, HttpSession session) {
        if (traces == null || traces.isEmpty()) return CaptureHandle.noop();
        try {
            return failOpen(traces.orElseThrow().current(principal, session));
        } catch (RuntimeException ignored) {
            return CaptureHandle.noop();
        }
    }

    public static CaptureHandle recover(
            Optional<PrivateAgentTraceService> traces, ResourceRef resource, String ownerUsername) {
        if (traces == null || traces.isEmpty() || resource == null) return CaptureHandle.noop();
        try {
            return failOpen(traces.orElseThrow().recover(resource, ownerUsername));
        } catch (RuntimeException ignored) {
            return CaptureHandle.noop();
        }
    }

    public static CaptureHandle failOpen(CaptureHandle delegate) {
        if (delegate == null || !enabled(delegate)) return CaptureHandle.noop();
        return delegate instanceof FailOpenCaptureHandle ? delegate : new FailOpenCaptureHandle(delegate);
    }

    private static boolean enabled(CaptureHandle delegate) {
        try {
            return delegate.enabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private record FailOpenCaptureHandle(CaptureHandle delegate) implements CaptureHandle {

        private FailOpenCaptureHandle {
            if (delegate == null) throw new IllegalArgumentException("private agent trace delegate is required");
        }

        @Override
        public boolean enabled() {
            return PrivateAgentTraceCapture.enabled(delegate);
        }

        @Override
        public Optional<UUID> traceId() {
            try {
                return delegate.traceId();
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }

        @Override
        public void userTurn(com.rulepilot.agenttrace.AgentTraceEvent.UserTurn event) {
            try {
                delegate.userTurn(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public void modelCallStarted(com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted event) {
            try {
                delegate.modelCallStarted(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public void modelTurn(com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn event) {
            try {
                delegate.modelTurn(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public void toolCall(com.rulepilot.agenttrace.AgentTraceEvent.ToolCall event) {
            try {
                delegate.toolCall(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public void toolObservation(com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation event) {
            try {
                delegate.toolObservation(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public void publication(com.rulepilot.agenttrace.AgentTraceEvent.Publication event) {
            try {
                delegate.publication(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public void bindingOrFailure(com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure event) {
            try {
                delegate.bindingOrFailure(event);
            } catch (RuntimeException ignored) {
                // Private diagnostics never replace the product result.
            }
        }

        @Override
        public boolean bind(ResourceRef resource) {
            try {
                return delegate.bind(resource);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
