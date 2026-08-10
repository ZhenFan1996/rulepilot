package com.rulepilot.teaching.application;

import com.rulepilot.document.UploadedRulebookTeachingHandoffs;
import com.rulepilot.document.UploadedRulebookTeachingHandoffs.ReadyHandoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Continues a player upload after document processing without relying on the originating browser page. */
@Service
@Profile("!test")
public class UploadedRulebookTeachingLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadedRulebookTeachingLauncher.class);

    private final UploadedRulebookTeachingHandoffs handoffs;
    private final TeachingPlanLauncher plans;
    private final int batchSize;

    public UploadedRulebookTeachingLauncher(
            UploadedRulebookTeachingHandoffs handoffs,
            TeachingPlanLauncher plans,
            @Value("${rulepilot.teaching.import-handoff.batch-size}") int batchSize) {
        if (batchSize < 1 || batchSize > 20) {
            throw new IllegalArgumentException("uploaded rulebook teaching handoff batch size is invalid");
        }
        this.handoffs = handoffs;
        this.plans = plans;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${rulepilot.teaching.import-handoff.fixed-delay}")
    synchronized void launchReadyHandoffs() {
        int unusable = handoffs.failUnusableDocuments();
        if (unusable > 0) {
            LOGGER.warn("Failed {} uploaded-rulebook teaching handoffs whose documents could not be processed", unusable);
        }
        for (ReadyHandoff handoff : handoffs.claimReady(batchSize)) {
            launch(handoff);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void recoverAndLaunch() {
        int interrupted = handoffs.failInterruptedLaunches();
        if (interrupted > 0) {
            LOGGER.warn("Marked {} interrupted uploaded-rulebook teaching launches for explicit retry", interrupted);
        }
        launchReadyHandoffs();
    }

    private void launch(ReadyHandoff handoff) {
        try {
            var launched = plans.launch(
                    handoff.documentVersionId(), handoff.learningGoal(), handoff.ownerUsername());
            handoffs.markLaunched(handoff.handoffId(), launched.assistantRunId());
        } catch (RuntimeException failure) {
            handoffs.markFailed(handoff.handoffId(), failureCode(failure));
            LOGGER.warn(
                    "Uploaded rulebook teaching launch failed for handoffId={} with {}",
                    handoff.handoffId(),
                    failure.getClass().getSimpleName());
        }
    }

    private String failureCode(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
                ? "TEACHING_HANDOFF_INVALID"
                : "TEACHING_HANDOFF_LAUNCH_FAILED";
    }
}
