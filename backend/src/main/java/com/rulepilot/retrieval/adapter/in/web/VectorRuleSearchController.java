package com.rulepilot.retrieval.adapter.in.web;

import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.domain.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/vector-search")
@Profile("!test")
public class VectorRuleSearchController {

    private final VectorRuleSearch search;

    public VectorRuleSearchController(VectorRuleSearch search) {
        this.search = search;
    }

    @GetMapping
    List<RuleEvidenceHit> search(
            @PathVariable UUID versionId,
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "5") int limit) {
        return search.search(versionId, query, limit);
    }
}
