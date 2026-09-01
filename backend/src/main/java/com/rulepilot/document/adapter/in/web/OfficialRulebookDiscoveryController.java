package com.rulepilot.document.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectMapper traceJson;
    private final Optional<PrivateAgentTraceService> privateTraces;

    public OfficialRulebookDiscoveryController(OfficialRulebookDiscoveryService discovery) {
        this(discovery, new ObjectMapper().findAndRegisterModules(), Optional.empty());
    }

    @Autowired
    public OfficialRulebookDiscoveryController(
            OfficialRulebookDiscoveryService discovery,
            ObjectMapper traceJson,
            Optional<PrivateAgentTraceService> privateTraces) {
        this.discovery = discovery;
        this.traceJson = traceJson;
        this.privateTraces = privateTraces == null ? Optional.empty() : privateTraces;
    }

    @GetMapping
    DiscoveryResponse discover(
            @RequestParam UUID editionId,
            @RequestParam(required = false) String language,
            Principal principal,
            HttpSession session) {
        CaptureHandle capture = PrivateAgentTraceCapture.current(privateTraces, principal, session);
        UUID requestOperationId = UUID.randomUUID();
        captureUserTurn(capture, editionId, language, requestOperationId);
        try {
            DiscoveryResponse response = response(
                    discovery.discover(editionId, language, capture, requestOperationId));
            capturePublication(capture, response, requestOperationId);
            return response;
        } catch (RuntimeException exception) {
            captureLifecycle(
                    capture,
                    requestOperationId,
                    LifecycleSignal.FAILURE,
                    "RULEBOOK_DISCOVERY_REQUEST_FAILED");
            throw exception;
        }
    }

    DiscoveryResponse discover(UUID editionId, String language) {
        return response(discovery.discover(editionId, language));
    }

    private DiscoveryResponse response(OfficialRulebookDiscoveryService.Result result) {
        return new DiscoveryResponse(
                result.configured(),
                DiscoveryIdentityResponse.from(result.identity()),
                result.candidates().stream().map(CandidateResponse::from).toList(),
                DiscoverySummaryResponse.from(result.discovery()));
    }

    private void captureUserTurn(
            CaptureHandle capture,
            UUID editionId,
            String language,
            UUID requestOperationId) {
        if (capture == null || !capture.enabled()) return;
        try {
            capture.userTurn(new UserTurn(
                    TraceEventContext.create(
                            Instant.now(),
                            JourneyStage.IMPORT,
                            requestOperationId,
                            null,
                            null),
                    "Find official rulebook candidates",
                    traceJson.writeValueAsString(new DiscoveryTraceRequest(editionId, language)),
                    traceLocale(language)));
        } catch (JsonProcessingException | RuntimeException ignored) {
            captureLifecycle(
                    capture,
                    requestOperationId,
                    LifecycleSignal.GAP,
                    "RULEBOOK_DISCOVERY_USER_TURN_CAPTURE_FAILED");
        }
    }

    private void capturePublication(
            CaptureHandle capture,
            DiscoveryResponse response,
            UUID requestOperationId) {
        if (capture == null || !capture.enabled()) return;
        try {
            capture.publication(new Publication(
                    TraceEventContext.create(
                            Instant.now(),
                            JourneyStage.IMPORT,
                            UUID.randomUUID(),
                            requestOperationId,
                            null),
                    PublicationChannel.IMPORT_CANDIDATES,
                    traceJson.writeValueAsString(response),
                    response.discovery().completion().name(),
                    List.of()));
        } catch (JsonProcessingException | RuntimeException ignored) {
            captureLifecycle(
                    capture,
                    requestOperationId,
                    LifecycleSignal.GAP,
                    "RULEBOOK_DISCOVERY_PUBLICATION_CAPTURE_FAILED");
        }
    }

    private static void captureLifecycle(
            CaptureHandle capture,
            UUID requestOperationId,
            LifecycleSignal signal,
            String code) {
        if (capture == null || requestOperationId == null || signal == null) return;
        try {
            if (!capture.enabled()) return;
            capture.bindingOrFailure(new BindingOrFailure(
                    TraceEventContext.create(
                            Instant.now(),
                            JourneyStage.IMPORT,
                            requestOperationId,
                            null,
                            null),
                    signal,
                    code,
                    null,
                    null));
        } catch (RuntimeException ignored) {
            // Optional private diagnostics never alter the authenticated discovery result.
        }
    }

    private static String traceLocale(String language) {
        String checked = language == null ? "" : language.strip();
        return checked.isBlank() || checked.length() > 40 ? "und" : checked;
    }

    private record DiscoveryTraceRequest(UUID editionId, String language) {}

    record DiscoveryResponse(
            boolean configured,
            DiscoveryIdentityResponse identity,
            List<CandidateResponse> candidates,
            DiscoverySummaryResponse discovery) {}

    record DiscoverySummaryResponse(
            OfficialRulebookDiscoveryService.DiscoveryCompletion completion,
            long elapsedMs,
            long totalBudgetMs,
            List<ProviderProgressResponse> providers) {
        static DiscoverySummaryResponse from(OfficialRulebookDiscoveryService.DiscoverySummary summary) {
            return new DiscoverySummaryResponse(
                    summary.completion(),
                    summary.elapsedMs(),
                    summary.totalBudgetMs(),
                    summary.providers().stream().map(ProviderProgressResponse::from).toList());
        }
    }

    record ProviderProgressResponse(
            OfficialRulebookDiscoveryService.DiscoveryProvider provider,
            OfficialRulebookDiscoveryService.DiscoveryProviderState state,
            long elapsedMs) {
        static ProviderProgressResponse from(OfficialRulebookDiscoveryService.ProviderProgress progress) {
            return new ProviderProgressResponse(progress.provider(), progress.state(), progress.elapsedMs());
        }
    }

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
