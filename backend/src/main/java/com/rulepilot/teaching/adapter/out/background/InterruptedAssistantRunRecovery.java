package com.rulepilot.teaching.adapter.out.background;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Fails orphaned work instead of replaying incomplete model/tool calls after a process restart. */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true")
class InterruptedAssistantRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(InterruptedAssistantRunRecovery.class);
    private final AssistantRuns runs;

    InterruptedAssistantRunRecovery(AssistantRuns runs) {
        this.runs = runs;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void recover() {
        int recovered = 0;
        for (AssistantRunMode mode : AssistantRunMode.values()) {
            recovered += runs.failInterrupted(mode);
        }
        if (recovered > 0) {
            log.info("Marked {} interrupted Agent runs as retryable failures", recovered);
        }
    }
}
