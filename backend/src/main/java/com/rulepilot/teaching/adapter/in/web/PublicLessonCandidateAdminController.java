package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.PublicLessonCandidateService;
import com.rulepilot.teaching.application.PublicLessonCandidateService.CandidateComparison;
import com.rulepilot.teaching.application.PublicLessonCandidateService.CandidateDecision;
import com.rulepilot.teaching.application.PublicLessonCandidateService.CandidateLaunch;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/public-lessons/{planId}/candidates")
@Profile("!test")
public class PublicLessonCandidateAdminController {

    private final PublicLessonCandidateService candidates;

    public PublicLessonCandidateAdminController(PublicLessonCandidateService candidates) {
        this.candidates = candidates;
    }

    @PostMapping
    ResponseEntity<CandidateLaunch> generate(@PathVariable UUID planId) {
        CandidateLaunch launch = candidates.launch(planId).orElseThrow(this::notFound);
        return launch.reused() ? ResponseEntity.ok(launch) : ResponseEntity.accepted().body(launch);
    }

    @GetMapping("/latest")
    CandidateComparison compare(@PathVariable UUID planId) {
        return candidates.latestComparison(planId).orElseThrow(this::notFound);
    }

    @PostMapping("/latest/apply-recommendation")
    CandidateDecision applyRecommendation(@PathVariable UUID planId) {
        return candidates.applyLatestRecommendation(planId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "public lesson candidate does not exist");
    }
}
