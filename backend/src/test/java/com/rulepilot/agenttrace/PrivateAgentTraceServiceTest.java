package com.rulepilot.agenttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.PrivateAgentTraceService.AccessCode;
import com.rulepilot.agenttrace.PrivateAgentTraceService.ExportSnapshot;
import com.rulepilot.agenttrace.PrivateAgentTraceService.TraceAccessException;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.StoredEvent;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceIntegrity;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceReadResult;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceSession;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class PrivateAgentTraceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private static final Principal ALICE = () -> "alice";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void capturesBindsSealsAndExportsACompletePrivateArtifact() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        PrivateAgentTraceService.TraceStatus started = service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID userOperation = UUID.randomUUID();
        UUID modelOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());

        capture.userTurn(new UserTurn(
                context(JourneyStage.ANSWER, userOperation, null, run),
                "Can I place this tile?",
                "{\"language\":\"en\"}",
                "en"));
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.ANSWER, modelOperation, userOperation, run),
                "openai",
                "answer-model",
                1,
                "answer-v3",
                "answer-schema-v2",
                "abc123",
                300,
                800));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.ANSWER, modelOperation, userOperation, run),
                "openai",
                "answer-model",
                1,
                "{\"shortVerdict\":\"Yes\"}",
                List.of(),
                "STOP",
                310,
                42,
                false));
        capture.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), userOperation, run),
                PublicationChannel.ANSWER,
                "{\"shortVerdict\":\"Yes\"}",
                "ANSWERED",
                List.of(UUID.randomUUID())));

        assertThat(capture.bind(run)).isTrue();
        assertThat(service.recover(run, "alice").enabled()).isTrue();
        assertThat(service.recover(run, "mallory").enabled()).isFalse();
        assertThat(service.seal(ALICE, session).state())
                .isEqualTo(PrivateAgentTraceService.CaptureState.SEALED);

        AgentTraceExporter.PreparedExport prepared =
                new AgentTraceExporter(json).prepare(service.export(ALICE, session));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        prepared.writeTo(output);
        ZipContents zip = unzip(output.toByteArray());
        JsonNode manifest = json.readTree(zip.manifest());

        assertThat(started.traceId()).isEqualTo(capture.traceId().orElseThrow());
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("eventCount").asLong()).isEqualTo(4);
        assertThat(zip.manifest()).doesNotContain("Can I place this tile?", "shortVerdict");
        assertThat(zip.events()).contains("Can I place this tile?", "shortVerdict", "MODEL_TURN");
    }

    @Test
    void treatsAnExplicitToolObservationAsTerminalBeforePublication() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID userOperation = UUID.randomUUID();
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.RECOMMENDATION_TURN, userOperation);

        capture.userTurn(new UserTurn(
                context(JourneyStage.RECOMMENDATION, userOperation, null, run),
                "Please recommend one game",
                "{\"requestedCount\":1}",
                "en"));
        capture.toolCall(new ToolCall(
                context(JourneyStage.RECOMMENDATION, toolOperation, userOperation, run),
                "call-1",
                "recommend_games",
                "{\"requestedCount\":1}",
                "{\"requestedCount\":1}",
                "recommendation-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.RECOMMENDATION, toolOperation, userOperation, run),
                "call-1",
                "recommend_games",
                "{\"outcome\":\"RECOMMENDATIONS\"}",
                "TERMINAL_RESPONSE",
                0,
                false,
                List.of()));
        capture.publication(new Publication(
                context(JourneyStage.RECOMMENDATION, userOperation, toolOperation, run),
                PublicationChannel.RECOMMENDATION,
                "{\"outcome\":\"RECOMMENDATIONS\"}",
                "RECOMMENDATIONS",
                List.of()));
        service.seal(ALICE, session);

        JsonNode manifest = json.readTree(new AgentTraceExporter(json)
                .prepare(service.export(ALICE, session))
                .manifestJson());

        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("incompleteReasons")).isEmpty();
    }

    @Test
    void doesNotTreatADirectlyParentedPublicationAsAToolObservation() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.RECOMMENDATION_TURN, UUID.randomUUID());
        capture.toolCall(new ToolCall(
                context(JourneyStage.RECOMMENDATION, toolOperation, null, run),
                "call-1",
                "recommend_games",
                "{\"requestedCount\":1}",
                "{\"requestedCount\":1}",
                "recommendation-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.publication(new Publication(
                context(JourneyStage.RECOMMENDATION, UUID.randomUUID(), toolOperation, run),
                PublicationChannel.RECOMMENDATION,
                "{\"outcome\":\"RECOMMENDATIONS\"}",
                "RECOMMENDATIONS",
                List.of()));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("TOOL_OPERATION_OPEN_");
    }

    @Test
    void acceptsAnImportCacheReplayWithAnExactCandidatePublicationAndNoFabricatedModelTurn() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID requestOperation = UUID.randomUUID();

        capture.userTurn(new UserTurn(
                context(JourneyStage.IMPORT, requestOperation, null, null),
                "Find official rulebook candidates",
                "{\"editionId\":\"00000000-0000-0000-0000-000000000042\",\"language\":\"en\"}",
                "en"));
        capture.bindingOrFailure(new BindingOrFailure(
                context(JourneyStage.IMPORT, requestOperation, null, null),
                LifecycleSignal.REPLAY,
                "RULEBOOK_DISCOVERY_CACHE_REUSED",
                null,
                null));
        capture.publication(new Publication(
                context(JourneyStage.IMPORT, UUID.randomUUID(), requestOperation, null),
                PublicationChannel.IMPORT_CANDIDATES,
                "{\"configured\":true,\"candidates\":[]}",
                "COMPLETE",
                List.of()));
        service.seal(ALICE, session);

        JsonNode manifest = manifest(service, session);
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("incompleteReasons")).isEmpty();
        assertThat(manifest.path("stages")).anySatisfy(stage ->
                assertThat(stage.asText()).isEqualTo("IMPORT"));
    }

    @Test
    void verifiesAnImportModelWebSearchAndExactCandidatePublicationAsOneCompleteWorkTree() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID requestOperation = UUID.randomUUID();
        UUID modelOperation = UUID.randomUUID();
        UUID toolOperation = UUID.randomUUID();

        capture.userTurn(new UserTurn(
                context(JourneyStage.IMPORT, requestOperation, null, null),
                "Find official rulebook candidates",
                "{\"language\":\"en\"}",
                "en"));
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.IMPORT, modelOperation, requestOperation, null),
                "responses-api",
                "search-model",
                1,
                "official-rulebook-discovery-initial-v1",
                "official-rulebook-candidates-v1",
                "abc123",
                100,
                500));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.IMPORT, modelOperation, requestOperation, null),
                "responses-api",
                "search-model",
                1,
                "{\"output\":[{\"type\":\"web_search_call\"}]}",
                List.of(new ModelToolCall(
                        "search-1", "web_search", "{\"query\":\"official rules\"}")),
                "RESPONSE_RECEIVED",
                100,
                30,
                false));
        capture.toolCall(new ToolCall(
                context(JourneyStage.IMPORT, toolOperation, modelOperation, null),
                "search-1",
                "web_search",
                "{\"query\":\"official rules\"}",
                "{\"query\":\"official rules\"}",
                "responses-built-in-web-search-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.IMPORT, toolOperation, modelOperation, null),
                "search-1",
                "web_search",
                "{\"sources\":[{\"url\":\"https://publisher.example/rules.pdf\"}]}",
                "OBSERVED",
                1,
                false,
                List.of()));
        capture.publication(new Publication(
                context(JourneyStage.IMPORT, UUID.randomUUID(), requestOperation, null),
                PublicationChannel.IMPORT_CANDIDATES,
                "{\"configured\":true,\"candidates\":[{\"url\":\"https://publisher.example/rules.pdf\"}]}",
                "COMPLETE",
                List.of()));
        service.seal(ALICE, session);

        JsonNode manifest = manifest(service, session);
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("incompleteReasons")).isEmpty();
    }

    @Test
    void rejectsAnImportPublicationThatUsesAnotherStagesChannel() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID requestOperation = UUID.randomUUID();

        capture.userTurn(new UserTurn(
                context(JourneyStage.IMPORT, requestOperation, null, null),
                "Find official rulebook candidates",
                "{\"language\":\"en\"}",
                "en"));
        capture.publication(new Publication(
                context(JourneyStage.IMPORT, UUID.randomUUID(), requestOperation, null),
                PublicationChannel.ANSWER,
                "{\"candidates\":[]}",
                "COMPLETE",
                List.of()));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("PUBLICATION_CHANNEL_STAGE_MISMATCH_IMPORT_ANSWER");
    }

    @Test
    void rejectsAnotherHttpSessionEvenForTheSameOwner() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession original = new MockHttpSession();
        service.start(ALICE, original);
        MockHttpSession other = new MockHttpSession();
        other.setAttribute(
                PrivateAgentTraceService.SESSION_ATTRIBUTE,
                original.getAttribute(PrivateAgentTraceService.SESSION_ATTRIBUTE));

        assertThatThrownBy(() -> service.status(ALICE, other))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_NOT_FOUND));
    }

    @Test
    void returnsNotFoundForAValidSessionPresentedByTheWrongOwner() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        Principal mallory = () -> "mallory";

        assertThatThrownBy(() -> service.status(mallory, session))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_NOT_FOUND));
        assertThatThrownBy(() -> service.seal(mallory, session))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_NOT_FOUND));
        assertThatThrownBy(() -> service.delete(mallory, session))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_NOT_FOUND));
    }

    @Test
    void failsOpenWhenTraceAppendIsUnavailableAndMarksTheArtifactIncomplete() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        store.setFailAppend(true);

        assertThatCode(() -> capture.userTurn(new UserTurn(
                        context(JourneyStage.RECOMMENDATION, UUID.randomUUID(), null, null),
                        "Recommend a game",
                        "{\"players\":4}",
                        "en")))
                .doesNotThrowAnyException();

        assertThat(capture.enabled()).isFalse();
        assertThat(service.status(ALICE, session).orElseThrow().integrity())
                .isEqualTo(PrivateAgentTraceService.CaptureIntegrity.INCOMPLETE);
    }

    @Test
    void marksASealedArtifactIncompleteWhenAnInFlightHandleAppendsLate() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle inFlight = service.current(ALICE, session);
        UUID userOperation = UUID.randomUUID();
        inFlight.userTurn(new UserTurn(
                context(JourneyStage.ANSWER, userOperation, null, null),
                "question",
                "{}",
                "en"));
        inFlight.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), userOperation, null),
                PublicationChannel.ANSWER,
                "{\"answer\":\"done\"}",
                "ANSWERED",
                List.of()));
        service.seal(ALICE, session);

        assertThatCode(() -> inFlight.modelCallStarted(modelStart(
                        UUID.randomUUID(),
                        1,
                        new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID()))))
                .doesNotThrowAnyException();

        PrivateAgentTraceService.TraceStatus status = service.status(ALICE, session).orElseThrow();
        assertThat(inFlight.enabled()).isFalse();
        assertThat(status.integrity()).isEqualTo(PrivateAgentTraceService.CaptureIntegrity.INCOMPLETE);
        assertThat(status.incompleteReason()).isEqualTo("LATE_EVENT_AFTER_SEAL");
        JsonNode manifest = manifest(service, session);
        assertThat(manifest.path("complete").asBoolean()).isFalse();
        assertThat(manifest.path("incompleteReasons").toString()).contains("LATE_EVENT_AFTER_SEAL");
    }

    @Test
    void marksTheArtifactIncompleteWhenADurableResourceBindingIsRejected() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setAllowedUsers(List.of("alice", "bob"));
        PrivateAgentTraceService service =
                new PrivateAgentTraceService(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        Principal bob = () -> "bob";
        MockHttpSession aliceSession = new MockHttpSession();
        MockHttpSession bobSession = new MockHttpSession();
        service.start(ALICE, aliceSession);
        service.start(bob, bobSession);
        CaptureHandle aliceCapture = service.current(ALICE, aliceSession);
        CaptureHandle bobCapture = service.current(bob, bobSession);
        ResourceRef sharedRun = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());

        assertThat(aliceCapture.bind(sharedRun)).isTrue();
        assertThat(bobCapture.bind(sharedRun)).isFalse();
        assertThat(bobCapture.enabled()).isFalse();
        assertThat(service.status(bob, bobSession).orElseThrow().integrity())
                .isEqualTo(PrivateAgentTraceService.CaptureIntegrity.INCOMPLETE);
        assertThat(service.status(bob, bobSession).orElseThrow().incompleteReason())
                .isEqualTo("BINDING_REJECTED");
    }

    @Test
    void isClosedByDefaultAndRefusesToStartWhenEncryptionIsUnavailable() {
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setAllowedUsers(List.of("alice"));
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        store.setAvailable(false);
        PrivateAgentTraceService service = new PrivateAgentTraceService(
                store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(properties.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.start(ALICE, new MockHttpSession()))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_UNAVAILABLE));
    }

    @Test
    void deniesStartWhenTheBoundedAllowlistIsEmptyOrDoesNotMatch() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        PrivateAgentTraceService service =
                new PrivateAgentTraceService(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.start(ALICE, new MockHttpSession()))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_NOT_FOUND));

        properties.setAllowedUsers(List.of("bob"));
        PrivateAgentTraceService nonMatching =
                new PrivateAgentTraceService(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> nonMatching.start(ALICE, new MockHttpSession()))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_NOT_FOUND));
    }

    @Test
    void rejectsAnUnboundedAllowedUserConfiguration() {
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setAllowedUsers(java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> "trace-user-" + index)
                .toList());

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("private agent trace configuration is invalid");
    }

    @Test
    void enforcesOneRetainedTracePerOwnerAcrossSessionsAndReleasesItAfterDelete() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession first = new MockHttpSession();
        MockHttpSession second = new MockHttpSession();
        service.start(ALICE, first);

        assertThatThrownBy(() -> service.start(ALICE, second))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.OWNER_TRACE_EXISTS));

        service.delete(ALICE, first);
        assertThat(service.start(ALICE, second).state())
                .isEqualTo(PrivateAgentTraceService.CaptureState.ACTIVE);
    }

    @Test
    void preservesSessionIdentityUntilARequestedDeleteIsVerified() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        Object traceId = session.getAttribute(PrivateAgentTraceService.SESSION_ATTRIBUTE);
        store.setFailDelete(true);

        assertThatThrownBy(() -> service.delete(ALICE, session))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_UNAVAILABLE));
        assertThat(session.getAttribute(PrivateAgentTraceService.SESSION_ATTRIBUTE)).isEqualTo(traceId);
        assertThat(session.getAttribute(PrivateAgentTraceService.DELETE_PENDING_ATTRIBUTE)).isEqualTo(traceId);

        store.setFailDelete(false);
        service.delete(ALICE, session);
        assertThat(session.getAttribute(PrivateAgentTraceService.SESSION_ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(PrivateAgentTraceService.DELETE_PENDING_ATTRIBUTE)).isNull();
    }

    @Test
    void rejectsConcurrentExportsForTheSameOwnerAndReleasesThePermitOnClose() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);

        PrivateAgentTraceService.ExportLease first = service.beginExport(ALICE, session);
        assertThatThrownBy(() -> service.beginExport(ALICE, session))
                .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AccessCode.TRACE_EXPORT_BUSY));

        first.close();
        assertThatCode(() -> {
                    try (PrivateAgentTraceService.ExportLease ignored = service.beginExport(ALICE, session)) {
                        // Acquiring again proves that close returned both the owner and global permits.
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    void capsConcurrentExportsAcrossOwnersAndReleasesTheGlobalPermit() {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setAllowedUsers(List.of("alice", "bob", "carol"));
        PrivateAgentTraceService service =
                new PrivateAgentTraceService(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        Principal bob = () -> "bob";
        Principal carol = () -> "carol";
        MockHttpSession aliceSession = new MockHttpSession();
        MockHttpSession bobSession = new MockHttpSession();
        MockHttpSession carolSession = new MockHttpSession();
        service.start(ALICE, aliceSession);
        service.start(bob, bobSession);
        service.start(carol, carolSession);

        try (PrivateAgentTraceService.ExportLease aliceExport = service.beginExport(ALICE, aliceSession);
                PrivateAgentTraceService.ExportLease bobExport = service.beginExport(bob, bobSession)) {
            assertThatThrownBy(() -> service.beginExport(carol, carolSession))
                    .isInstanceOfSatisfying(TraceAccessException.class, exception ->
                            assertThat(exception.code()).isEqualTo(AccessCode.TRACE_EXPORT_BUSY));
        }
        assertThatCode(() -> {
                    try (PrivateAgentTraceService.ExportLease ignored = service.beginExport(carol, carolSession)) {
                        // Both global permits were returned by the completed exports.
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    void correlatesModelAttemptsIndependentlyWhenAnOperationIsReused() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID operation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        for (int attempt = 1; attempt <= 2; attempt++) {
            capture.modelCallStarted(modelStart(operation, attempt, run));
            capture.modelTurn(modelTurn(operation, attempt, run, List.of()));
        }
        capture.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), operation, run),
                PublicationChannel.ANSWER,
                "{\"answer\":\"done\"}",
                "ANSWERED",
                List.of()));
        service.seal(ALICE, session);

        JsonNode manifest = manifest(service, session);
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("incompleteReasons")).isEmpty();
    }

    @Test
    void doesNotUseAnEarlierAttemptFailureToCloseALaterAttempt() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID operation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        capture.modelCallStarted(modelStart(operation, 1, run));
        capture.modelTurn(modelTurn(operation, 1, run, List.of()));
        capture.bindingOrFailure(new BindingOrFailure(
                context(JourneyStage.ANSWER, operation, null, run),
                LifecycleSignal.FAILURE,
                "FIRST_ATTEMPT_PARSE_FAILED",
                run,
                null));
        capture.modelCallStarted(modelStart(operation, 2, run));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("MODEL_OPERATION_OPEN_" + operation + "_ATTEMPT_2");
    }

    @Test
    void requiresEveryRawModelToolCallToHaveAValidatedTypedTerminalDisposition() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID modelOperation = UUID.randomUUID();
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        capture.modelCallStarted(modelStart(modelOperation, 1, run));
        capture.modelTurn(modelTurn(
                modelOperation,
                1,
                run,
                List.of(new ModelToolCall("call-1", "search_rules", "{\"query\":\"setup\"}"))));
        capture.toolCall(new ToolCall(
                context(JourneyStage.ANSWER, toolOperation, modelOperation, run),
                "call-1",
                "search_rules",
                "{\"query\":\"setup\"}",
                "{\"query\":\"setup\"}",
                "search-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.ANSWER, toolOperation, modelOperation, run),
                "call-1",
                "search_rules",
                "{\"matches\":1}",
                "OBSERVED",
                1,
                false,
                List.of()));
        capture.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), modelOperation, run),
                PublicationChannel.ANSWER,
                "{\"answer\":\"done\"}",
                "ANSWERED",
                List.of()));
        service.seal(ALICE, session);

        JsonNode manifest = manifest(service, session);
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("incompleteReasons")).isEmpty();
    }

    @Test
    void reportsMissingAndUncheckedRawToolDispositions() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID missingOperation = UUID.randomUUID();
        UUID uncheckedOperation = UUID.randomUUID();
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        capture.modelCallStarted(modelStart(missingOperation, 1, run));
        capture.modelTurn(modelTurn(
                missingOperation,
                1,
                run,
                List.of(new ModelToolCall("missing-call", "search_rules", "{}"))));
        capture.modelCallStarted(modelStart(uncheckedOperation, 1, run));
        capture.modelTurn(modelTurn(
                uncheckedOperation,
                1,
                run,
                List.of(new ModelToolCall("unchecked-call", "read_rule", "{}"))));
        capture.toolCall(new ToolCall(
                context(JourneyStage.ANSWER, toolOperation, uncheckedOperation, run),
                "unchecked-call",
                "read_rule",
                "{}",
                "",
                "read-v1",
                "abc123",
                ToolArgumentValidation.UNCHECKED));
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.ANSWER, toolOperation, uncheckedOperation, run),
                "unchecked-call",
                "read_rule",
                "{}",
                "OBSERVED",
                1,
                false,
                List.of()));
        service.seal(ALICE, session);

        String reasons = manifest(service, session).path("incompleteReasons").toString();
        assertThat(reasons)
                .contains("RAW_TOOL_CALL_MISSING_TYPED_DISPOSITION_")
                .contains("TOOL_CALL_VALIDATION_UNCHECKED_")
                .contains("RAW_TOOL_CALL_UNVALIDATED_");
    }

    @Test
    void doesNotGuessThatAJourneyPublicationTerminatesAnUnobservedDescendantTool() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID userOperation = UUID.randomUUID();
        UUID modelOperation = UUID.randomUUID();
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        capture.userTurn(new UserTurn(
                context(JourneyStage.ANSWER, userOperation, null, run), "question", "{}", "en"));
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.ANSWER, modelOperation, userOperation, run),
                "openai",
                "trace-test-model",
                1,
                "trace-test-v1",
                "trace-test-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.ANSWER, modelOperation, userOperation, run),
                "openai",
                "trace-test-model",
                1,
                "",
                List.of(new ModelToolCall("call-1", "search_rules", "{}")),
                "TOOL_CALLS",
                10,
                5,
                false));
        capture.toolCall(new ToolCall(
                context(JourneyStage.ANSWER, toolOperation, modelOperation, run),
                "call-1",
                "search_rules",
                "{}",
                "{}",
                "search-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), userOperation, run),
                PublicationChannel.ANSWER,
                "{\"answer\":\"done\"}",
                "ANSWERED",
                List.of()));
        service.seal(ALICE, session);

        String reasons = manifest(service, session).path("incompleteReasons").toString();
        assertThat(reasons).contains("TOOL_OPERATION_OPEN_").contains("RAW_TOOL_CALL_OPEN_");
    }

    @Test
    void requiresATeachingPublicationOrTerminalFailureAfterTeachingModelWork() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID operation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                1,
                "teaching-v1",
                "teaching-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                1,
                "lesson",
                List.of(),
                "STOP",
                10,
                5,
                false));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("WORK_PUBLICATION_MISSING_TEACHING_RESOURCE_TEACHING_RUN_" + run.id());
    }

    @Test
    void acceptsAnExactTeachingPlanPublicationAfterOutlineModelWork() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID operation = UUID.randomUUID();
        ResourceRef preparationRun = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, operation, null, preparationRun),
                "openai",
                "teaching-outline-model",
                1,
                "teaching-outline-v1",
                "teaching-plan-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, operation, null, preparationRun),
                "openai",
                "teaching-outline-model",
                1,
                "{\"sections\":[]}",
                List.of(),
                "STOP",
                10,
                5,
                false));
        capture.publication(new Publication(
                context(JourneyStage.TEACHING, UUID.randomUUID(), operation, preparationRun),
                PublicationChannel.TEACHING_PLAN,
                "{\"plan\":\"persisted\"}",
                "TEACHING_PLAN_READY",
                List.of()));
        service.seal(ALICE, session);

        JsonNode manifest = manifest(service, session);
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("incompleteReasons")).isEmpty();
    }

    @Test
    void doesNotLetAnUnrelatedSameStagePublicationOrFailureCloseTeachingWork() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID workOperation = UUID.randomUUID();
        ResourceRef unfinishedRun = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        ResourceRef unrelatedRun = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        UUID unrelatedOperation = UUID.randomUUID();
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, workOperation, null, unfinishedRun),
                "openai",
                "teaching-model",
                1,
                "teaching-v1",
                "teaching-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, workOperation, null, unfinishedRun),
                "openai",
                "teaching-model",
                1,
                "lesson",
                List.of(),
                "STOP",
                10,
                5,
                false));
        capture.bindingOrFailure(new BindingOrFailure(
                context(JourneyStage.TEACHING, unrelatedOperation, null, unrelatedRun),
                LifecycleSignal.FAILURE,
                "UNRELATED_RUN_FAILED",
                unrelatedRun,
                null));
        capture.publication(new Publication(
                context(JourneyStage.TEACHING, UUID.randomUUID(), unrelatedOperation, unrelatedRun),
                PublicationChannel.TEACHING_LESSON,
                "{\"lesson\":\"unrelated\"}",
                "PUBLISHED",
                List.of()));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("WORK_PUBLICATION_MISSING_TEACHING_RESOURCE_TEACHING_RUN_" + unfinishedRun.id());
    }

    @Test
    void doesNotLetAnEarlierSameRunPublicationCloseLaterTeachingModelWork() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID operation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                1,
                "teaching-v1",
                "teaching-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                1,
                "section one",
                List.of(),
                "STOP",
                10,
                5,
                false));
        capture.publication(new Publication(
                context(JourneyStage.TEACHING, UUID.randomUUID(), operation, run),
                PublicationChannel.TEACHING_SECTION,
                "{\"section\":1}",
                "PUBLISHED",
                List.of()));
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                2,
                "teaching-v1",
                "teaching-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                2,
                "section two",
                List.of(),
                "STOP",
                10,
                5,
                false));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("WORK_PUBLICATION_MISSING_TEACHING_RESOURCE_TEACHING_RUN_" + run.id());
    }

    @Test
    void doesNotLetAnEarlierFailureCloseLaterWorkOnTheSameOperation() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID operation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID());
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                1,
                "teaching-v1",
                "teaching-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                1,
                "discarded candidate",
                List.of(),
                "STOP",
                10,
                5,
                false));
        capture.bindingOrFailure(new BindingOrFailure(
                context(JourneyStage.TEACHING, operation, null, run),
                LifecycleSignal.FAILURE,
                "FIRST_ATTEMPT_REJECTED",
                run,
                null));
        capture.modelCallStarted(new ModelCallStarted(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                2,
                "teaching-v1",
                "teaching-schema-v1",
                "abc123",
                10,
                100));
        capture.modelTurn(new ModelTurn(
                context(JourneyStage.TEACHING, operation, null, run),
                "openai",
                "teaching-model",
                2,
                "unpublished repair",
                List.of(),
                "STOP",
                10,
                5,
                false));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("WORK_PUBLICATION_MISSING_TEACHING_RESOURCE_TEACHING_RUN_" + run.id());
    }

    @Test
    void exportsAndAnalyzesByAtomicSequenceWhenWallClockAndInputOrderAreReversed() throws Exception {
        UUID traceId = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        UserTurn userTurn = new UserTurn(
                TraceEventContext.create(
                        NOW.plusMillis(900), JourneyStage.ANSWER, operation, null, run),
                "Does sequence beat wall time?",
                "{}",
                "en");
        Publication publication = new Publication(
                TraceEventContext.create(
                        NOW.plusMillis(100), JourneyStage.ANSWER, UUID.randomUUID(), operation, run),
                PublicationChannel.ANSWER,
                "{\"answer\":\"yes\"}",
                "ANSWERED",
                List.of());
        TraceSession sealed = new TraceSession(
                traceId,
                "opaque-owner-identity",
                "session-digest",
                TraceState.SEALED,
                TraceIntegrity.COMPLETE,
                "",
                NOW,
                NOW.plusSeconds(30),
                NOW.plusSeconds(60),
                NOW.plusSeconds(1),
                2,
                200);
        TraceReadResult read = new TraceReadResult(
                sealed,
                List.of(new StoredEvent(2, publication), new StoredEvent(1, userTurn)),
                List.of(),
                2,
                200);

        AgentTraceExporter.PreparedExport prepared = new AgentTraceExporter(json)
                .prepare(new ExportSnapshot(sealed, read));

        List<byte[]> eventLines = prepared.eventLines();
        assertThat(eventLines).hasSize(2);
        assertThat(json.readTree(eventLines.get(0)).path("sequence").asLong()).isEqualTo(1L);
        assertThat(json.readTree(eventLines.get(1)).path("sequence").asLong()).isEqualTo(2L);
        assertThat(json.readTree(prepared.manifestJson()).path("complete").asBoolean()).isTrue();
    }

    @Test
    void marksANonContiguousAtomicSequenceIncompleteEvenWhenCountsMatch() throws Exception {
        UUID traceId = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        UserTurn userTurn = new UserTurn(
                context(JourneyStage.ANSWER, operation, null, null), "question", "{}", "en");
        Publication publication = new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), operation, null),
                PublicationChannel.ANSWER,
                "{\"answer\":\"yes\"}",
                "ANSWERED",
                List.of());
        TraceSession sealed = new TraceSession(
                traceId,
                "opaque-owner-identity",
                "session-digest",
                TraceState.SEALED,
                TraceIntegrity.COMPLETE,
                "",
                NOW,
                NOW.plusSeconds(30),
                NOW.plusSeconds(60),
                NOW.plusSeconds(1),
                2,
                200);
        TraceReadResult read = new TraceReadResult(
                sealed,
                List.of(new StoredEvent(1, userTurn), new StoredEvent(3, publication)),
                List.of(),
                2,
                200);

        JsonNode manifest = json.readTree(new AgentTraceExporter(json)
                .prepare(new ExportSnapshot(sealed, read))
                .manifestJson());

        assertThat(manifest.path("complete").asBoolean()).isFalse();
        assertThat(manifest.path("incompleteReasons").toString()).contains("SEQUENCE_GAP_AT_2");
    }

    @Test
    void rejectsAToolObservationThatPrecedesItsTypedCall() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.ANSWER, toolOperation, null, run),
                "call-1",
                "search_rules",
                "{\"matches\":1}",
                "OBSERVED",
                1,
                false,
                List.of()));
        capture.toolCall(new ToolCall(
                context(JourneyStage.ANSWER, toolOperation, null, run),
                "call-1",
                "search_rules",
                "{\"query\":\"setup\"}",
                "{\"query\":\"setup\"}",
                "search-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), toolOperation, run),
                PublicationChannel.ANSWER,
                "{\"answer\":\"done\"}",
                "ANSWERED",
                List.of()));
        service.seal(ALICE, session);

        String reasons = manifest(service, session).path("incompleteReasons").toString();
        assertThat(reasons)
                .contains("TOOL_OBSERVATION_BEFORE_CALL_")
                .contains("TOOL_OPERATION_OPEN_");
    }

    @Test
    void doesNotLetAPublicationPrecedeItsModelVisibleToolObservation() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        UUID toolOperation = UUID.randomUUID();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        capture.toolCall(new ToolCall(
                context(JourneyStage.ANSWER, toolOperation, null, run),
                "call-1",
                "search_rules",
                "{\"query\":\"setup\"}",
                "{\"query\":\"setup\"}",
                "search-v1",
                "abc123",
                ToolArgumentValidation.ACCEPTED));
        capture.publication(new Publication(
                context(JourneyStage.ANSWER, UUID.randomUUID(), toolOperation, run),
                PublicationChannel.ANSWER,
                "{\"answer\":\"premature\"}",
                "ANSWERED",
                List.of()));
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.ANSWER, toolOperation, null, run),
                "call-1",
                "search_rules",
                "{\"matches\":1}",
                "OBSERVED",
                1,
                false,
                List.of()));
        service.seal(ALICE, session);

        assertThat(manifest(service, session).path("incompleteReasons").toString())
                .contains("WORK_PUBLICATION_MISSING_ANSWER_RESOURCE_ASSISTANT_RUN_" + run.id());
    }

    @Test
    void detectsOrphansAndDuplicateStartsTerminalsAsIncomplete() throws Exception {
        InMemoryPrivateAgentTraceStore store = new InMemoryPrivateAgentTraceStore();
        PrivateAgentTraceService service = service(store);
        MockHttpSession session = new MockHttpSession();
        service.start(ALICE, session);
        CaptureHandle capture = service.current(ALICE, session);
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        UUID duplicate = UUID.randomUUID();
        ModelCallStarted start = modelStart(duplicate, 1, run);
        ModelTurn turn = modelTurn(duplicate, 1, run, List.of());
        capture.modelCallStarted(start);
        capture.modelCallStarted(start);
        capture.modelTurn(turn);
        capture.modelTurn(turn);
        capture.modelTurn(modelTurn(UUID.randomUUID(), 2, run, List.of()));
        UUID orphanTool = UUID.randomUUID();
        capture.toolObservation(new ToolObservation(
                context(JourneyStage.ANSWER, orphanTool, duplicate, run),
                "orphan-call",
                "search_rules",
                "{}",
                "OBSERVED",
                0,
                false,
                List.of()));
        TraceEventContext publicationContext = context(JourneyStage.ANSWER, UUID.randomUUID(), duplicate, run);
        Publication publication = new Publication(
                publicationContext,
                PublicationChannel.ANSWER,
                "{\"answer\":\"done\"}",
                "ANSWERED",
                List.of());
        capture.publication(publication);
        capture.publication(publication);
        service.seal(ALICE, session);

        String reasons = manifest(service, session).path("incompleteReasons").toString();
        assertThat(reasons)
                .contains("MODEL_START_DUPLICATE_")
                .contains("MODEL_TURN_DUPLICATE_")
                .contains("MODEL_TURN_ORPHAN_")
                .contains("TOOL_OBSERVATION_ORPHAN_")
                .contains("PUBLICATION_DUPLICATE_");
    }

    private ModelCallStarted modelStart(UUID operation, int attempt, ResourceRef resource) {
        return new ModelCallStarted(
                context(JourneyStage.ANSWER, operation, null, resource),
                "openai",
                "trace-test-model",
                attempt,
                "trace-test-v1",
                "trace-test-schema-v1",
                "abc123",
                10,
                100);
    }

    private ModelTurn modelTurn(
            UUID operation, int attempt, ResourceRef resource, List<ModelToolCall> toolCalls) {
        return new ModelTurn(
                context(JourneyStage.ANSWER, operation, null, resource),
                "openai",
                "trace-test-model",
                attempt,
                toolCalls.isEmpty() ? "done" : "",
                toolCalls,
                toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS",
                10,
                5,
                false);
    }

    private JsonNode manifest(PrivateAgentTraceService service, MockHttpSession session) throws Exception {
        return json.readTree(new AgentTraceExporter(json)
                .prepare(service.export(ALICE, session))
                .manifestJson());
    }

    private PrivateAgentTraceService service(InMemoryPrivateAgentTraceStore store) {
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setAllowedUsers(List.of("alice"));
        return new PrivateAgentTraceService(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TraceEventContext context(
            JourneyStage stage, UUID operationId, UUID parentOperationId, ResourceRef resource) {
        return TraceEventContext.create(NOW, stage, operationId, parentOperationId, resource);
    }

    private ZipContents unzip(byte[] bytes) throws Exception {
        String manifest = "";
        String events = "";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String content = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if ("manifest.json".equals(entry.getName())) manifest = content;
                if ("events.ndjson".equals(entry.getName())) events = content;
            }
        }
        return new ZipContents(manifest, events);
    }

    private record ZipContents(String manifest, String events) {}
}
