package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.FailedTeachingPreparationRemovalService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-preparation-failures")
@Profile("!test")
public class FailedTeachingPreparationRemovalController {

    private final FailedTeachingPreparationRemovalService removals;

    public FailedTeachingPreparationRemovalController(FailedTeachingPreparationRemovalService removals) {
        this.removals = removals;
    }

    @DeleteMapping("/official-imports/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeOfficialImport(@PathVariable UUID jobId, Principal principal) {
        removals.removeOfficialImport(jobId, principal.getName());
    }

    @DeleteMapping("/uploads/{handoffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeUpload(@PathVariable UUID handoffId, Principal principal) {
        removals.removeUpload(handoffId, principal.getName());
    }
}
