package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.MediaConsistencyService;
import com.rulepilot.teaching.domain.MediaConsistencyReport;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/media-consistency")
@Profile("!test")
public class MediaConsistencyController {

    private final MediaConsistencyService consistency;

    public MediaConsistencyController(MediaConsistencyService consistency) {
        this.consistency = consistency;
    }

    @GetMapping
    MediaConsistencyReport evaluate(@PathVariable UUID planId) {
        return consistency.evaluate(planId);
    }
}
