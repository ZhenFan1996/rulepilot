package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.IllustratedLessonService;
import com.rulepilot.teaching.application.TeachingPlanSummary;
import com.rulepilot.teaching.application.IllustratedLessonLauncher;
import com.rulepilot.teaching.application.IllustratedLessonLauncher.LessonLaunch;
import com.rulepilot.teaching.application.LessonLocalizationService;
import com.rulepilot.teaching.application.RulebookIconGlossaryService;
import com.rulepilot.teaching.application.VisualLessonEnrichmentService.VisualEnrichmentLaunch;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.assistant.PlayerLocale;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/illustrated-lessons")
@Profile("!test")
public class IllustratedLessonController {

    private final IllustratedLessonService lessons;
    private final IllustratedLessonLauncher launcher;
    private final TeachingPlanOwnerGuard owners;
    private final LessonLocalizationService localizations;
    private final RulebookIconGlossaryService iconGlossary;

    public IllustratedLessonController(
            IllustratedLessonService lessons,
            IllustratedLessonLauncher launcher,
            TeachingPlanOwnerGuard owners,
            LessonLocalizationService localizations,
            RulebookIconGlossaryService iconGlossary) {
        this.lessons = lessons;
        this.launcher = launcher;
        this.owners = owners;
        this.localizations = localizations;
        this.iconGlossary = iconGlossary;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    LessonLaunch create(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return launcher.launch(planId, principal.getName());
    }

    @PostMapping("/latest/visuals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    VisualEnrichmentLaunch enrichVisuals(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        lessons.latest(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lesson does not exist"));
        return launcher.enrichLatest(planId, principal.getName());
    }

    @PostMapping("/latest/icon-glossary")
    @ResponseStatus(HttpStatus.ACCEPTED)
    VisualEnrichmentLaunch prepareIconGlossary(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        lessons.latest(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lesson does not exist"));
        return launcher.prepareIconGlossary(planId, principal.getName());
    }

    @GetMapping("/latest/icon-glossary")
    RulebookIconGlossaryService.GlossaryView iconGlossary(
            @PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return iconGlossary.viewOwned(planId, principal.getName());
    }

    @GetMapping("/latest/icon-glossary/icons/{occurrenceId}/image")
    ResponseEntity<byte[]> iconImage(
            @PathVariable UUID planId, @PathVariable UUID occurrenceId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        var crop = iconGlossary.cropOwned(planId, occurrenceId, principal.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(crop.mediaType()))
                .cacheControl(CacheControl.noStore())
                .body(crop.content());
    }

    @GetMapping("/latest")
    IllustratedLesson latest(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return lessons.latest(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lesson does not exist"));
    }

    @GetMapping("/latest/summary")
    TeachingPlanSummary.LessonProgress latestSummary(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return lessons.latestProgress(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lesson does not exist"));
    }

    @PostMapping("/latest/localizations/{language}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    LessonLocalizationService.LocalizationView prepareLocalization(
            @PathVariable UUID planId, @PathVariable String language, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return localizations.prepare(planId, principal.getName(), PlayerLocale.fromRequest(language));
    }

    @GetMapping("/latest/localizations/{language}")
    LessonLocalizationService.LocalizationView localization(
            @PathVariable UUID planId, @PathVariable String language, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        var source = lessons.latest(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lesson does not exist"));
        return localizations.view(source, PlayerLocale.fromRequest(language));
    }
}
