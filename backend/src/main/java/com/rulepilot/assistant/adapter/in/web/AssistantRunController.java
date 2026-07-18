package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.AssistantRuns;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/assistant-runs")
@Profile("!test")
public class AssistantRunController {

    private final AssistantRuns runs;

    public AssistantRunController(AssistantRuns runs) {
        this.runs = runs;
    }

    @GetMapping("/{runId}")
    AssistantRuns.RunDetails get(@PathVariable UUID runId, Principal principal) {
        return runs.findOwned(runId, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "assistant run does not exist"));
    }
}
