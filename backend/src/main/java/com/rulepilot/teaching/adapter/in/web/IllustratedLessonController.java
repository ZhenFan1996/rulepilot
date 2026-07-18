package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.IllustratedLessonService;
import com.rulepilot.teaching.application.IllustratedLessonService.LessonCreation;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
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
    private final TeachingPlanOwnerGuard owners;

    public IllustratedLessonController(IllustratedLessonService lessons, TeachingPlanOwnerGuard owners) {
        this.lessons = lessons;
        this.owners = owners;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LessonCreation create(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return lessons.create(planId, principal.getName());
    }

    @GetMapping("/latest")
    IllustratedLesson latest(@PathVariable UUID planId, Principal principal) {
        owners.requireOwned(planId, principal.getName());
        return lessons.latest(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lesson does not exist"));
    }
}
