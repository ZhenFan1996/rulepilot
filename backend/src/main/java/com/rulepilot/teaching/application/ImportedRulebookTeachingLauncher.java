package com.rulepilot.teaching.application;

import com.rulepilot.document.RulebookTeachingHandoffs;
import com.rulepilot.document.RulebookTeachingHandoffs.ReadyHandoff;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Continues an explicitly requested rulebook-import journey after the bound document becomes ready. */
@Service
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
public class ImportedRulebookTeachingLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportedRulebookTeachingLauncher.class);

    private final RulebookTeachingHandoffs handoffs;
    private final TeachingPlanLauncher plans;
    private final int batchSize;

    public ImportedRulebookTeachingLauncher(
            RulebookTeachingHandoffs handoffs,
            TeachingPlanLauncher plans,
            @Value("${rulepilot.teaching.import-handoff.batch-size}") int batchSize) {
        if (batchSize < 1 || batchSize > 20) {
            throw new IllegalArgumentException("imported rulebook teaching handoff batch size is invalid");
        }
        this.handoffs = handoffs;
        this.plans = plans;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${rulepilot.teaching.import-handoff.fixed-delay}",
            scheduler = "teachingHandoffScheduler")
    synchronized void launchReadyHandoffs() {
        int unusable = handoffs.failUnusableDocuments();
        if (unusable > 0) {
            LOGGER.warn("Failed {} imported-rulebook teaching handoffs whose documents could not be processed", unusable);
        }
        var reconciliation = handoffs.reconcileLaunched(batchSize);
        if (reconciliation.restarted() > 0) {
            LOGGER.warn(
                    "Restarted {} imported-rulebook teaching handoffs whose persisted Teaching result was missing or unusable",
                    reconciliation.restarted());
        }
        if (reconciliation.exhausted() > 0) {
            LOGGER.warn(
                    "Stopped {} imported-rulebook teaching handoffs after the single automatic recovery still produced no reusable result",
                    reconciliation.exhausted());
        }
        launch(handoffs.claimReady(batchSize));
    }

    /** Event-driven wake-up; the atomic persistent claim makes repeated notifications harmless. */
    public synchronized void dispatchReadyHandoffs(UUID documentVersionId) {
        if (documentVersionId == null) throw new IllegalArgumentException("ready document version is required");
        // A READY event cannot correspond to a failed document. Keep the prompt path to one indexed claim and leave
        // the global unusable-document sweep to scheduled reconciliation.
        launch(handoffs.claimReadyForDocument(documentVersionId, batchSize));
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void recoverAndLaunch() {
        int interrupted = handoffs.failInterruptedLaunches();
        if (interrupted > 0) {
            LOGGER.warn("Marked {} interrupted imported-rulebook teaching launches for explicit retry", interrupted);
        }
        launchReadyHandoffs();
    }

    private void launch(java.util.List<ReadyHandoff> claimed) {
        for (ReadyHandoff handoff : claimed) launch(handoff);
    }

    private void launch(ReadyHandoff handoff) {
        try {
            var launched = plans.launch(
                    handoff.documentVersionId(), handoff.learningGoal(), handoff.ownerUsername());
            handoffs.markLaunched(handoff.importJobId(), launched.assistantRunId());
        } catch (RuntimeException failure) {
            handoffs.markFailed(handoff.importJobId(), failureCode(failure));
            LOGGER.warn(
                    "Imported rulebook teaching launch failed for importJobId={} with {}",
                    handoff.importJobId(),
                    failure.getClass().getSimpleName());
        }
    }

    private String failureCode(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
                ? "TEACHING_HANDOFF_INVALID"
                : "TEACHING_HANDOFF_LAUNCH_FAILED";
    }
}
