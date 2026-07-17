package com.rulepilot.ingestion.adapter.in.web;

import com.rulepilot.ingestion.application.RuleStructureService;
import com.rulepilot.ingestion.application.RuleStructureService.StructureView;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/rule-structure")
@Profile("!test")
public class RuleStructureController {

    private final RuleStructureService structures;

    public RuleStructureController(RuleStructureService structures) {
        this.structures = structures;
    }

    @GetMapping
    StructureView structure(@PathVariable UUID versionId) {
        return structures.structure(versionId);
    }
}
