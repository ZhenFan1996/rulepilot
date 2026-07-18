package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.ChapterVideoService;
import com.rulepilot.teaching.domain.ChapterVideo;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/video")
@Profile("!test")
public class ChapterVideoController {

    private final ChapterVideoService videos;
    private final TeachingPlanOwnerGuard owners;

    public ChapterVideoController(ChapterVideoService videos, TeachingPlanOwnerGuard owners) {
        this.videos = videos;
        this.owners = owners;
    }

    @GetMapping
    ChapterVideo compose(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return videos.compose(planId);
    }
}
