package com.rulepilot.teaching.adapter.out.background;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true")
class InterruptedTeachingRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(InterruptedTeachingRunRecovery.class);
    private final AssistantRuns runs;

    InterruptedTeachingRunRecovery(AssistantRuns runs) {
        this.runs = runs;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        int recovered = runs.failInterrupted(AssistantRunMode.TEACHING)
                + runs.failInterrupted(AssistantRunMode.TEACHING_PREPARATION);
        if (recovered > 0) {
            log.info("Marked {} interrupted teaching generation runs as retryable failures", recovered);
        }
    }
}
