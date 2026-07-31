package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.PublicIconGlossaryBackfillService;
import com.rulepilot.teaching.application.PublicIconGlossaryBackfillService.BackfillLaunch;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/public-lessons")
@Profile("!test")
public class PublicIconGlossaryAdminController {

    private final PublicIconGlossaryBackfillService backfills;

    public PublicIconGlossaryAdminController(PublicIconGlossaryBackfillService backfills) {
        this.backfills = backfills;
    }

    @PostMapping("/{planId}/icon-glossary")
    ResponseEntity<BackfillLaunch> backfill(@PathVariable UUID planId) {
        BackfillLaunch launch = backfills.launch(planId)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "public lesson does not exist"));
        return launch.accepted()
                ? ResponseEntity.accepted().body(launch)
                : ResponseEntity.ok(launch);
    }
}
