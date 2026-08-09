package com.rulepilot.document.adapter.out.source;

import com.rulepilot.document.application.OfficialRulebookImportJobService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
class InterruptedOfficialRulebookImportRecovery implements ApplicationListener<ApplicationReadyEvent> {

    private final OfficialRulebookImportJobService jobs;

    InterruptedOfficialRulebookImportRecovery(OfficialRulebookImportJobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        jobs.failInterrupted();
    }
}
