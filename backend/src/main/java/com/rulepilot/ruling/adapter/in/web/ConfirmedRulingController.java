package com.rulepilot.ruling.adapter.in.web;

import com.rulepilot.ruling.application.ConfirmedRulingService;
import com.rulepilot.ruling.domain.ConfirmedRuling;
import com.rulepilot.ruling.domain.RulingConfidence;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/confirmed-rulings")
public class ConfirmedRulingController {

    private final ConfirmedRulingService rulings;

    public ConfirmedRulingController(ConfirmedRulingService rulings) {
        this.rulings = rulings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConfirmedRuling confirm(@RequestBody ConfirmRulingRequest request, Principal principal) {
        return rulings.confirm(
                request.editionId(), request.documentVersionId(), request.expansionIds(),
                request.question(), request.shortVerdict(), request.explanation(), request.citationChunkIds(),
                request.exceptions(), request.confidence(), principal.getName());
    }

    @GetMapping("/{rulingId}")
    ConfirmedRuling get(@PathVariable UUID rulingId, Principal principal) {
        return rulings.get(rulingId, principal.getName());
    }

    record ConfirmRulingRequest(
            UUID editionId,
            UUID documentVersionId,
            Set<UUID> expansionIds,
            String question,
            String shortVerdict,
            String explanation,
            List<UUID> citationChunkIds,
            List<String> exceptions,
            RulingConfidence confidence) {}
}
