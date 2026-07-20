package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.application.AnswerRegressionService;
import com.rulepilot.assistant.domain.AnswerRegressionReport;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/document-versions/{versionId}/answer-regressions")
public class AnswerRegressionController {

    private final AnswerRegressionService regressions;

    public AnswerRegressionController(AnswerRegressionService regressions) {
        this.regressions = regressions;
    }

    @PostMapping
    AnswerRegressionReport evaluate(
            @PathVariable UUID versionId,
            @RequestParam(defaultValue = "1") int attempts,
            @RequestParam(required = false) String caseId,
            Principal principal) {
        return regressions.evaluate(versionId, principal.getName(), attempts, caseId);
    }
}
