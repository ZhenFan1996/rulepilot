package com.rulepilot.retrieval.adapter.in.web;

import com.rulepilot.retrieval.application.RetrievalEvaluationService;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/document-versions/{versionId}/retrieval-evaluation")
@Profile("!test")
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService evaluation;

    public RetrievalEvaluationController(RetrievalEvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @PostMapping
    RetrievalEvaluationReport evaluate(@PathVariable UUID versionId) {
        return evaluation.evaluate(versionId);
    }
}
