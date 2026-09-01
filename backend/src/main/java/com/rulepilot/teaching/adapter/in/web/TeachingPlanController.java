package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.teaching.application.TeachingPlanService;
import com.rulepilot.teaching.application.TeachingPlanLauncher;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/teaching-plans")
@Profile("!test")
public class TeachingPlanController {

    private final TeachingPlanService plans;
    private final TeachingPlanLauncher launcher;
    private final Optional<PrivateAgentTraceService> privateTraces;

    @Autowired
    public TeachingPlanController(
            TeachingPlanService plans,
            TeachingPlanLauncher launcher,
            Optional<PrivateAgentTraceService> privateTraces) {
        this.plans = plans;
        this.launcher = launcher;
        this.privateTraces = privateTraces == null ? Optional.empty() : privateTraces;
    }

    public TeachingPlanController(TeachingPlanService plans, TeachingPlanLauncher launcher) {
        this(plans, launcher, Optional.empty());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    TeachingPlanLauncher.PlanLaunch create(
            @PathVariable UUID versionId,
            @RequestBody CreatePlanRequest request,
            Principal principal,
            HttpSession session) {
        CaptureHandle capture = PrivateAgentTraceCapture.current(privateTraces, principal, session);
        return capture.enabled()
                ? launcher.launch(
                        versionId,
                        request.learningGoal(),
                        principal.getName(),
                        capture)
                : launcher.launch(versionId, request.learningGoal(), principal.getName());
    }

    TeachingPlanLauncher.PlanLaunch create(
            UUID versionId, CreatePlanRequest request, Principal principal) {
        return launcher.launch(versionId, request.learningGoal(), principal.getName());
    }

    @GetMapping("/latest")
    TeachingPlan latest(@PathVariable UUID versionId, Principal principal) {
        return plans.latest(versionId, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "teaching plan does not exist"));
    }

    record CreatePlanRequest(String learningGoal) {}
}
