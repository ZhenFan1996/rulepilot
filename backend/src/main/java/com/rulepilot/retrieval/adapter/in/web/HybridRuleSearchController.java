package com.rulepilot.retrieval.adapter.in.web;

import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/hybrid-search")
@Profile("!test")
public class HybridRuleSearchController {

    private final HybridRuleSearch search;

    public HybridRuleSearchController(HybridRuleSearch search) {
        this.search = search;
    }

    @GetMapping
    List<HybridEvidenceHit> search(
            @PathVariable UUID versionId,
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "") Set<String> sectionType,
            @RequestParam(required = false) String currentSectionType) {
        return search.search(versionId, query, new RetrievalOptions(limit, sectionType, currentSectionType));
    }
}
