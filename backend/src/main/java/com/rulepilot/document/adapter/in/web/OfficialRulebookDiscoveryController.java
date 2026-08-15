package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.application.OfficialRulebookDiscoveryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/rulebook-candidates")
@Profile("!test")
public class OfficialRulebookDiscoveryController {

    private final OfficialRulebookDiscoveryService discovery;

    public OfficialRulebookDiscoveryController(OfficialRulebookDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping
    DiscoveryResponse discover(
            @RequestParam UUID editionId, @RequestParam(required = false) String language) {
        var result = discovery.discover(editionId, language);
        return new DiscoveryResponse(
                result.configured(),
                DiscoveryIdentityResponse.from(result.identity()),
                result.candidates().stream().map(CandidateResponse::from).toList());
    }

    record DiscoveryResponse(
            boolean configured,
            DiscoveryIdentityResponse identity,
            List<CandidateResponse> candidates) {}

    record DiscoveryIdentityResponse(
            UUID editionId,
            String gameName,
            String editionName,
            String language) {
        static DiscoveryIdentityResponse from(OfficialRulebookDiscoveryService.DiscoveryIdentity identity) {
            return new DiscoveryIdentityResponse(
                    identity.editionId(), identity.gameName(), identity.editionName(), identity.language());
        }
    }

    record CandidateResponse(
            String title,
            String url,
            String publisher,
            String language,
            String edition,
            String sourceDomain,
            boolean officialDomainVerified,
            boolean languageVerified,
            OfficialRulebookDiscoveryService.SourceType sourceType,
            OfficialRulebookDiscoveryService.AcquisitionMode acquisitionMode,
            OfficialRulebookDiscoveryService.SourceCapability capability,
            List<OfficialRulebookDiscoveryService.CapabilityEvidence> capabilityEvidence,
            Instant capabilityCheckedAt,
            OfficialRulebookDiscoveryService.SourceAction nextAction) {
        static CandidateResponse from(OfficialRulebookDiscoveryService.Candidate candidate) {
            return new CandidateResponse(
                    candidate.title(),
                    candidate.url(),
                    candidate.publisher(),
                    candidate.language(),
                    candidate.edition(),
                    candidate.sourceDomain(),
                    candidate.officialDomainVerified(),
                    candidate.languageVerified(),
                    candidate.sourceType(),
                    candidate.acquisitionMode(),
                    candidate.capability(),
                    candidate.capabilityEvidence(),
                    candidate.capabilityCheckedAt(),
                    candidate.nextAction());
        }
    }
}
