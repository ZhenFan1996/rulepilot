package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.AssistantRuns;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Full safe execution audit for administrators; hidden reasoning and raw evidence are never persisted here. */
@RestController
@RequestMapping("/api/admin/assistant-runs")
@Profile("!test")
public class AssistantRunAdminController {

    private final AssistantRuns runs;

    public AssistantRunAdminController(AssistantRuns runs) {
        this.runs = runs;
    }

    @GetMapping("/{runId}/audit")
    AssistantRuns.RunDetails audit(@PathVariable UUID runId) {
        return runs.findForAdministrativeAudit(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "assistant run does not exist"));
    }
}
