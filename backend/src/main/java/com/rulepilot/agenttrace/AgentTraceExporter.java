package com.rulepilot.agenttrace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.PrivateAgentTraceService.ExportSnapshot;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.StoredEvent;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceIntegrity;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceSession;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceState;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AgentTraceExporter {

    private static final int EXPORT_SCHEMA_VERSION = 2;
    private static final EnumSet<LifecycleSignal> TERMINAL_SIGNALS =
            EnumSet.of(LifecycleSignal.FAILURE, LifecycleSignal.GAP, LifecycleSignal.FALLBACK);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper json;

    public AgentTraceExporter(ObjectMapper json) {
        if (json == null) throw new IllegalArgumentException("agent trace export mapper is required");
        this.json = json;
    }

    PreparedExport prepare(ExportSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("agent trace export snapshot is required");
        List<StoredEvent> sequenceOrdered = snapshot.readResult().events().stream()
                .sorted(Comparator.comparingLong(StoredEvent::sequence))
                .toList();
        List<byte[]> lines = new ArrayList<>(sequenceOrdered.size());
        MessageDigest digest = sha256();
        for (StoredEvent stored : sequenceOrdered) {
            try {
                byte[] line = json.writeValueAsBytes(new ExportedEvent(stored.sequence(), stored.event()));
                lines.add(line);
                digest.update(line);
                digest.update((byte) '\n');
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("agent trace export event could not be serialized", exception);
            }
        }
        TraceManifest manifest = manifest(
                snapshot, sequenceOrdered, HexFormat.of().formatHex(digest.digest()));
        try {
            byte[] manifestJson = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            String shortId = snapshot.session().traceId().toString().substring(0, 8);
            String filename = "rulepilot-agent-trace-" + FILE_TIME.format(snapshot.session().createdAt()) + "-" + shortId
                    + ".zip";
            return new PreparedExport(snapshot.session().traceId(), filename, manifestJson, lines);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent trace export manifest could not be serialized", exception);
        }
    }

    private TraceManifest manifest(
            ExportSnapshot snapshot, List<StoredEvent> sequenceOrdered, String eventsSha256) {
        TraceSession session = snapshot.session();
        Set<String> reasons = new LinkedHashSet<>(snapshot.readResult().problemCodes());
        if (session.integrity() != TraceIntegrity.COMPLETE) {
            reasons.add(session.incompleteReason().isBlank() ? session.integrity().name() : session.incompleteReason());
        }
        if (session.state() == TraceState.ACTIVE) reasons.add("CAPTURE_STILL_ACTIVE");
        if (snapshot.readResult().events().isEmpty()) reasons.add("NO_EVENTS");
        if (session.eventCount() != snapshot.readResult().observedEventCount()) {
            reasons.add("EVENT_COUNT_MISMATCH");
        }
        if (session.storedBytes() != snapshot.readResult().observedStoredBytes()) {
            reasons.add("STORED_BYTES_MISMATCH");
        }
        if (snapshot.readResult().events().size() != snapshot.readResult().observedEventCount()) {
            reasons.add("DECRYPTED_EVENT_COUNT_MISMATCH");
        }

        Analysis analysis = analyze(sequenceOrdered);
        reasons.addAll(analysis.reasons());
        boolean complete = reasons.isEmpty() && session.state() == TraceState.SEALED;
        return new TraceManifest(
                EXPORT_SCHEMA_VERSION,
                session.traceId(),
                session.createdAt(),
                session.captureUntil(),
                session.sealedAt(),
                session.expiresAt(),
                session.state().name(),
                complete,
                List.copyOf(reasons),
                analysis.stages().stream().map(Enum::name).sorted().toList(),
                session.eventCount(),
                sequenceOrdered.size(),
                snapshot.readResult().observedEventCount(),
                session.storedBytes(),
                snapshot.readResult().observedStoredBytes(),
                eventsSha256,
                true,
                "Contains private user, model, tool, and publication data. Do not commit or publish this file.");
    }

    private Analysis analyze(List<StoredEvent> events) {
        Set<String> reasons = new LinkedHashSet<>();
        EnumSet<JourneyStage> stages = EnumSet.noneOf(JourneyStage.class);
        Map<WorkScope, Long> workScopes = new LinkedHashMap<>();
        Map<ModelKey, List<ModelStart>> modelStarts = new LinkedHashMap<>();
        Map<ModelKey, List<ModelTerminal>> modelTurns = new LinkedHashMap<>();
        Map<ToolKey, List<SequencedToolCall>> toolCalls = new LinkedHashMap<>();
        Map<ToolKey, List<SequencedToolObservation>> observations = new LinkedHashMap<>();
        Map<UUID, List<Long>> userTurns = new LinkedHashMap<>();
        Map<PublicationKey, Integer> publications = new LinkedHashMap<>();
        List<SequencedPublication> publicationEvents = new ArrayList<>();
        List<SequencedTerminal> terminalEvents = new ArrayList<>();
        List<RawCall> rawCalls = new ArrayList<>();
        List<TypedCall> typedCalls = new ArrayList<>();
        Map<UUID, Integer> eventIds = new LinkedHashMap<>();
        Map<UUID, Long> knownOperations = new LinkedHashMap<>();
        Map<UUID, UUID> operationParents = new LinkedHashMap<>();
        Map<UUID, List<Long>> terminalScopes = new LinkedHashMap<>();
        Map<UUID, List<Long>> directFailures = new HashMap<>();

        long expectedSequence = 1;
        long previousSequence = 0;
        for (StoredEvent stored : events) {
            if (stored.sequence() == previousSequence) {
                reasons.add("SEQUENCE_DUPLICATE_" + stored.sequence());
            } else {
                if (stored.sequence() != expectedSequence) reasons.add("SEQUENCE_GAP_AT_" + expectedSequence);
                expectedSequence = stored.sequence() + 1;
                previousSequence = stored.sequence();
            }
            AgentTraceEvent event = stored.event();
            eventIds.merge(event.context().eventId(), 1, Integer::sum);
            stages.add(event.context().stage());
            UUID operation = event.context().operationId();
            if (!(event instanceof Publication)) {
                knownOperations.merge(operation, stored.sequence(), Math::min);
                UUID parent = event.context().parentOperationId();
                if (parent != null) {
                    UUID existing = operationParents.putIfAbsent(operation, parent);
                    if (existing != null && !existing.equals(parent)) {
                        reasons.add("OPERATION_PARENT_CONFLICT_" + operation);
                    }
                }
            }

            if (event instanceof ModelCallStarted started) {
                recordWork(workScopes, event, stored.sequence());
                add(modelStarts, new ModelKey(operation, started.attempt()), new ModelStart(started, stored.sequence()));
            }
            if (event instanceof ModelTurn turn) {
                recordWork(workScopes, event, stored.sequence());
                ModelKey modelKey = new ModelKey(operation, turn.attempt());
                add(modelTurns, modelKey, new ModelTerminal(turn, stored.sequence()));
                for (int index = 0; index < turn.toolCalls().size(); index++) {
                    ModelToolCall raw = turn.toolCalls().get(index);
                    rawCalls.add(new RawCall(
                            new RawCallKey(operation, turn.attempt(), raw.callId()),
                            raw.name(),
                            stored.sequence(),
                            index));
                }
            }
            if (event instanceof ToolCall call) {
                recordWork(workScopes, event, stored.sequence());
                ToolKey key = new ToolKey(operation, call.callId());
                add(toolCalls, key, new SequencedToolCall(call, stored.sequence()));
                typedCalls.add(new TypedCall(
                        key, event.context().parentOperationId(), call.toolName(), stored.sequence()));
            }
            if (event instanceof ToolObservation observation) {
                recordWork(workScopes, event, stored.sequence());
                add(
                        observations,
                        new ToolKey(operation, observation.callId()),
                        new SequencedToolObservation(observation, stored.sequence()));
            }
            if (event instanceof UserTurn) {
                userTurns.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(stored.sequence());
            }
            if (event instanceof Publication publication) {
                publications.merge(
                        new PublicationKey(operation, event.context().parentOperationId(), publication.channel()),
                        1,
                        Integer::sum);
                publicationEvents.add(new SequencedPublication(publication, stored.sequence()));
                if (!validPublicationChannel(event.context().stage(), publication.channel())) {
                    reasons.add("PUBLICATION_CHANNEL_STAGE_MISMATCH_"
                            + event.context().stage()
                            + "_"
                            + publication.channel());
                }
            }
            if (event instanceof BindingOrFailure lifecycle && TERMINAL_SIGNALS.contains(lifecycle.signal())) {
                terminalEvents.add(new SequencedTerminal(lifecycle, stored.sequence()));
                directFailures.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(stored.sequence());
                terminalScopes.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(stored.sequence());
                if (event.context().parentOperationId() != null) {
                    terminalScopes
                            .computeIfAbsent(event.context().parentOperationId(), ignored -> new ArrayList<>())
                            .add(stored.sequence());
                }
                if (lifecycle.signal() == LifecycleSignal.GAP) reasons.add("EXPLICIT_GAP_" + lifecycle.code());
            }
        }

        analyzeModels(modelStarts, modelTurns, directFailures, reasons);
        eventIds.forEach((eventId, count) -> {
            if (count > 1) reasons.add("EVENT_ID_DUPLICATE_" + eventId);
        });
        analyzePublications(publications, publicationEvents, knownOperations, reasons);
        analyzeTools(toolCalls, observations, terminalScopes, reasons);
        analyzeRawCalls(rawCalls, typedCalls, toolCalls, observations, terminalScopes, reasons);
        analyzeUserTurns(userTurns, publicationEvents, terminalScopes, reasons);
        for (Map.Entry<WorkScope, Long> entry : workScopes.entrySet()) {
            WorkScope work = entry.getKey();
            long lastWorkSequence = entry.getValue();
            boolean published = publicationEvents.stream()
                    .anyMatch(publication -> publicationCompletes(
                            work, lastWorkSequence, publication, operationParents));
            boolean failed = terminalEvents.stream()
                    .anyMatch(terminal -> terminalCompletes(
                            work, lastWorkSequence, terminal, operationParents));
            if (!published && !failed) {
                reasons.add("WORK_PUBLICATION_MISSING_" + workIdentity(work));
            }
        }
        return new Analysis(reasons, stages);
    }

    private boolean validPublicationChannel(JourneyStage stage, PublicationChannel channel) {
        if (stage == null || channel == null) return false;
        return switch (stage) {
            case RECOMMENDATION -> channel == PublicationChannel.RECOMMENDATION
                    || channel == PublicationChannel.FALLBACK;
            case IMPORT -> channel == PublicationChannel.IMPORT_CANDIDATES;
            case TEACHING -> channel == PublicationChannel.TEACHING_PLAN
                    || channel == PublicationChannel.TEACHING_SECTION
                    || channel == PublicationChannel.TEACHING_LESSON
                    || channel == PublicationChannel.FALLBACK;
            case ANSWER -> channel == PublicationChannel.ANSWER
                    || channel == PublicationChannel.FALLBACK;
        };
    }

    private boolean publicationCompletes(
            WorkScope work,
            long lastWorkSequence,
            SequencedPublication publication,
            Map<UUID, UUID> operationParents) {
        if (publication.sequence() <= lastWorkSequence) return false;
        Publication event = publication.event();
        if (work.stage() != event.context().stage()) return false;
        if (work.resource() != null && work.resource().equals(event.context().resource())) return true;
        return relatedOperations(work.operationId(), event.context(), operationParents);
    }

    private boolean terminalCompletes(
            WorkScope work,
            long lastWorkSequence,
            SequencedTerminal terminal,
            Map<UUID, UUID> operationParents) {
        return terminal.sequence() > lastWorkSequence
                && work.stage() == terminal.event().context().stage()
                && relatedOperations(work.operationId(), terminal.event().context(), operationParents);
    }

    private boolean relatedOperations(
            UUID workOperation,
            AgentTraceEvent.TraceEventContext terminal,
            Map<UUID, UUID> operationParents) {
        Set<UUID> workScopes = ancestors(workOperation, null, operationParents);
        Set<UUID> terminalScopes =
                ancestors(terminal.operationId(), terminal.parentOperationId(), operationParents);
        return workScopes.stream().anyMatch(terminalScopes::contains);
    }

    private Set<UUID> ancestors(UUID operation, UUID explicitParent, Map<UUID, UUID> operationParents) {
        Set<UUID> ancestors = new LinkedHashSet<>();
        UUID current = operation;
        while (current != null && ancestors.add(current)) {
            UUID mapped = operationParents.get(current);
            current = mapped == null && current.equals(operation) ? explicitParent : mapped;
        }
        return ancestors;
    }

    private String workIdentity(WorkScope work) {
        if (work.resource() != null) {
            return work.stage() + "_RESOURCE_" + work.resource().type() + "_" + work.resource().id();
        }
        return work.stage() + "_OPERATION_" + work.operationId();
    }

    private void analyzeModels(
            Map<ModelKey, List<ModelStart>> starts,
            Map<ModelKey, List<ModelTerminal>> turns,
            Map<UUID, List<Long>> directFailures,
            Set<String> reasons) {
        Set<ModelStart> matchedStarts = new LinkedHashSet<>();
        for (Map.Entry<ModelKey, List<ModelStart>> entry : starts.entrySet()) {
            ModelKey key = entry.getKey();
            if (entry.getValue().size() > 1) reasons.add("MODEL_START_DUPLICATE_" + modelIdentity(key));
        }
        for (Map.Entry<ModelKey, List<ModelTerminal>> entry : turns.entrySet()) {
            ModelKey key = entry.getKey();
            if (entry.getValue().size() > 1) reasons.add("MODEL_TURN_DUPLICATE_" + modelIdentity(key));
            for (ModelTerminal terminal : entry.getValue()) {
                ModelStart matched = starts.getOrDefault(key, List.of()).stream()
                        .filter(start -> !matchedStarts.contains(start) && start.sequence() < terminal.sequence())
                        .max(Comparator.comparingLong(ModelStart::sequence))
                        .orElse(null);
                if (matched == null) {
                    reasons.add("MODEL_TURN_ORPHAN_" + modelIdentity(key));
                    continue;
                }
                matchedStarts.add(matched);
                if (!matched.event().providerId().equals(terminal.event().providerId())
                        || !matched.event().modelId().equals(terminal.event().modelId())) {
                    reasons.add("MODEL_IDENTITY_MISMATCH_" + modelIdentity(key));
                }
            }
        }
        directFailures.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(sequence -> new ModelFailure(entry.getKey(), sequence)))
                .sorted(Comparator.comparingLong(ModelFailure::sequence))
                .forEach(failure -> starts.entrySet().stream()
                        .filter(entry -> entry.getKey().operationId().equals(failure.operationId()))
                        .flatMap(entry -> entry.getValue().stream())
                        .filter(start -> !matchedStarts.contains(start) && start.sequence() < failure.sequence())
                        .max(Comparator.comparingLong(ModelStart::sequence))
                        .ifPresent(matchedStarts::add));
        starts.forEach((key, values) -> {
            if (values.stream().anyMatch(start -> !matchedStarts.contains(start))) {
                reasons.add("MODEL_OPERATION_OPEN_" + modelIdentity(key));
            }
        });
    }

    private void analyzePublications(
            Map<PublicationKey, Integer> publications,
            List<SequencedPublication> publicationEvents,
            Map<UUID, Long> knownOperations,
            Set<String> reasons) {
        for (Map.Entry<PublicationKey, Integer> entry : publications.entrySet()) {
            PublicationKey key = entry.getKey();
            if (entry.getValue() > 1) reasons.add("PUBLICATION_DUPLICATE_" + publicationIdentity(key));
        }
        for (SequencedPublication publication : publicationEvents) {
            Publication event = publication.event();
            Long operationSequence = knownOperations.get(event.context().operationId());
            Long parentSequence = event.context().parentOperationId() == null
                    ? null
                    : knownOperations.get(event.context().parentOperationId());
            boolean correlated = operationSequence != null || parentSequence != null;
            boolean followsOperation = operationSequence != null && operationSequence < publication.sequence()
                    || parentSequence != null && parentSequence < publication.sequence();
            PublicationKey key = new PublicationKey(
                    event.context().operationId(), event.context().parentOperationId(), event.channel());
            if (!correlated) {
                reasons.add("PUBLICATION_ORPHAN_" + publicationIdentity(key));
            } else if (!followsOperation) {
                reasons.add("PUBLICATION_PRECEDES_OPERATION_" + publicationIdentity(key));
            }
        }
    }

    private void analyzeTools(
            Map<ToolKey, List<SequencedToolCall>> calls,
            Map<ToolKey, List<SequencedToolObservation>> observations,
            Map<UUID, List<Long>> terminalScopes,
            Set<String> reasons) {
        for (Map.Entry<ToolKey, List<SequencedToolCall>> entry : calls.entrySet()) {
            ToolKey key = entry.getKey();
            List<SequencedToolCall> capturedCalls = entry.getValue();
            List<SequencedToolObservation> capturedObservations = observations.getOrDefault(key, List.of());
            if (capturedCalls.size() > 1) reasons.add("TOOL_CALL_DUPLICATE_" + toolIdentity(key));
            if (capturedCalls.stream()
                    .anyMatch(call -> call.event().validation() == ToolArgumentValidation.UNCHECKED)) {
                reasons.add("TOOL_CALL_VALIDATION_UNCHECKED_" + toolIdentity(key));
            }
            if (capturedObservations.size() > 1) {
                reasons.add("TOOL_OBSERVATION_DUPLICATE_" + toolIdentity(key));
            }
            for (SequencedToolCall call : capturedCalls) {
                boolean observedAfter = capturedObservations.stream()
                        .anyMatch(observation -> observation.sequence() > call.sequence());
                boolean terminal = observedAfter
                        || hasTerminalAfter(terminalScopes, key.operationId(), call.sequence());
                if (!terminal) reasons.add("TOOL_OPERATION_OPEN_" + toolIdentity(key));
                for (SequencedToolObservation observation : capturedObservations) {
                    if (!call.event().toolName().equals(observation.event().toolName())) {
                        reasons.add("TOOL_IDENTITY_MISMATCH_" + toolIdentity(key));
                    }
                }
            }
        }
        for (Map.Entry<ToolKey, List<SequencedToolObservation>> entry : observations.entrySet()) {
            ToolKey key = entry.getKey();
            if (entry.getValue().size() > 1) reasons.add("TOOL_OBSERVATION_DUPLICATE_" + toolIdentity(key));
            List<SequencedToolCall> capturedCalls = calls.getOrDefault(key, List.of());
            for (SequencedToolObservation observation : entry.getValue()) {
                if (capturedCalls.isEmpty()) {
                    reasons.add("TOOL_OBSERVATION_ORPHAN_" + toolIdentity(key));
                } else if (capturedCalls.stream().noneMatch(call -> call.sequence() < observation.sequence())) {
                    reasons.add("TOOL_OBSERVATION_BEFORE_CALL_" + toolIdentity(key));
                }
            }
        }
    }

    private void analyzeRawCalls(
            List<RawCall> rawCalls,
            List<TypedCall> typedCalls,
            Map<ToolKey, List<SequencedToolCall>> calls,
            Map<ToolKey, List<SequencedToolObservation>> observations,
            Map<UUID, List<Long>> terminalScopes,
            Set<String> reasons) {
        Map<RawCallKey, Integer> rawCounts = new LinkedHashMap<>();
        rawCalls.forEach(raw -> rawCounts.merge(raw.key(), 1, Integer::sum));
        rawCounts.forEach((key, count) -> {
            if (count > 1) reasons.add("RAW_TOOL_CALL_DUPLICATE_" + rawIdentity(key));
        });

        Map<RawCall, TypedCall> matches = new LinkedHashMap<>();
        for (TypedCall typed : typedCalls) {
            RawCall best = null;
            for (RawCall raw : rawCalls) {
                if (matches.containsKey(raw)
                        || raw.sequence() >= typed.sequence()
                        || !raw.key().operationId().equals(typed.parentOperationId())
                        || !raw.key().callId().equals(typed.key().callId())
                        || !raw.toolName().equals(typed.toolName())) {
                    continue;
                }
                if (best == null || raw.sequence() > best.sequence()
                        || raw.sequence() == best.sequence() && raw.ordinal() > best.ordinal()) {
                    best = raw;
                }
            }
            if (best != null) matches.put(best, typed);
        }

        for (RawCall raw : rawCalls) {
            if (hasTerminalAfter(terminalScopes, raw.key().operationId(), raw.sequence())) continue;
            TypedCall matched = matches.get(raw);
            if (matched == null) {
                reasons.add("RAW_TOOL_CALL_MISSING_TYPED_DISPOSITION_" + rawIdentity(raw.key()));
                continue;
            }
            List<SequencedToolCall> matchedCalls = calls.getOrDefault(matched.key(), List.of());
            boolean validated = matchedCalls.stream()
                    .anyMatch(call -> call.sequence() == matched.sequence()
                            && call.event().validation() != ToolArgumentValidation.UNCHECKED);
            boolean terminal = observations.getOrDefault(matched.key(), List.of()).stream()
                            .anyMatch(observation -> observation.sequence() > matched.sequence())
                    || hasTerminalAfter(terminalScopes, matched.key().operationId(), matched.sequence());
            if (!validated) reasons.add("RAW_TOOL_CALL_UNVALIDATED_" + rawIdentity(raw.key()));
            if (!terminal) reasons.add("RAW_TOOL_CALL_OPEN_" + rawIdentity(raw.key()));
        }
    }

    private void analyzeUserTurns(
            Map<UUID, List<Long>> userTurns,
            List<SequencedPublication> publications,
            Map<UUID, List<Long>> terminalScopes,
            Set<String> reasons) {
        for (Map.Entry<UUID, List<Long>> entry : userTurns.entrySet()) {
            UUID operation = entry.getKey();
            if (entry.getValue().size() > 1) reasons.add("USER_TURN_DUPLICATE_" + operation);
            long lastTurnSequence = entry.getValue().stream().mapToLong(Long::longValue).max().orElseThrow();
            boolean published = publications.stream().anyMatch(publication ->
                    publication.sequence() > lastTurnSequence
                            && (operation.equals(publication.event().context().operationId())
                                    || operation.equals(publication.event().context().parentOperationId())));
            if (!published && !hasTerminalAfter(terminalScopes, operation, lastTurnSequence)) {
                reasons.add("USER_TURN_OPEN_" + operation);
            }
        }
    }

    private boolean hasTerminalAfter(
            Map<UUID, List<Long>> terminalScopes, UUID operationId, long sequence) {
        return terminalScopes.getOrDefault(operationId, List.of()).stream()
                .anyMatch(terminalSequence -> terminalSequence > sequence);
    }

    private void recordWork(Map<WorkScope, Long> workScopes, AgentTraceEvent event, long sequence) {
        workScopes.merge(WorkScope.from(event), sequence, Math::max);
    }

    private <K, V> void add(Map<K, List<V>> values, K key, V value) {
        values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    private String modelIdentity(ModelKey key) {
        return key.operationId() + "_ATTEMPT_" + key.attempt();
    }

    private String toolIdentity(ToolKey key) {
        return key.operationId() + "_CALL_" + shortHash(key.callId());
    }

    private String rawIdentity(RawCallKey key) {
        return modelIdentity(new ModelKey(key.operationId(), key.attempt())) + "_CALL_" + shortHash(key.callId());
    }

    private String publicationIdentity(PublicationKey key) {
        return key.operationId() + "_PARENT_"
                + (key.parentOperationId() == null ? "NONE" : key.parentOperationId()) + "_" + key.channel();
    }

    private String shortHash(String value) {
        String digest = HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
        return digest.substring(0, 12);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static final class PreparedExport {
        private final UUID traceId;
        private final String filename;
        private final byte[] manifestJson;
        private final List<byte[]> eventLines;

        private PreparedExport(UUID traceId, String filename, byte[] manifestJson, List<byte[]> eventLines) {
            if (traceId == null || filename == null || filename.isBlank() || manifestJson == null
                    || manifestJson.length == 0 || eventLines == null) {
                throw new IllegalArgumentException("prepared agent trace export is invalid");
            }
            if (eventLines.stream().anyMatch(value -> value == null || value.length == 0)) {
                throw new IllegalArgumentException("prepared agent trace export contains an invalid event");
            }
            this.traceId = traceId;
            this.filename = filename;
            this.manifestJson = manifestJson;
            this.eventLines = List.copyOf(eventLines);
        }

        public UUID traceId() {
            return traceId;
        }

        public String filename() {
            return filename;
        }

        public byte[] manifestJson() {
            return manifestJson.clone();
        }

        public List<byte[]> eventLines() {
            return eventLines.stream().map(value -> value.clone()).toList();
        }

        public void writeTo(OutputStream output) throws IOException {
            if (output == null) throw new IllegalArgumentException("agent trace export output is required");
            ZipOutputStream zip = new ZipOutputStream(output);
            writeEntry(zip, "manifest.json", manifestJson);
            zip.putNextEntry(entry("events.ndjson"));
            for (byte[] line : eventLines) {
                zip.write(line);
                zip.write('\n');
            }
            zip.closeEntry();
            zip.finish();
        }

        private void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
            zip.putNextEntry(entry(name));
            zip.write(content);
            zip.closeEntry();
        }

        private ZipEntry entry(String name) {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(0);
            return entry;
        }
    }

    record ExportedEvent(long sequence, AgentTraceEvent event) {
        ExportedEvent {
            if (sequence < 1 || event == null) throw new IllegalArgumentException("exported trace event is invalid");
        }
    }

    public record TraceManifest(
            int schemaVersion,
            UUID traceId,
            Instant createdAt,
            Instant captureUntil,
            Instant sealedAt,
            Instant expiresAt,
            String state,
            boolean complete,
            List<String> incompleteReasons,
            List<String> stages,
            long eventCount,
            long exportedEventCount,
            long observedEncryptedEventCount,
            long storedEncryptedBytes,
            long observedEncryptedBytes,
            String eventsSha256,
            boolean sensitive,
            String handlingNotice) {
        public TraceManifest {
            incompleteReasons = incompleteReasons == null ? List.of() : List.copyOf(incompleteReasons);
            stages = stages == null ? List.of() : stages.stream().sorted(Comparator.naturalOrder()).toList();
        }
    }

    private record Analysis(Set<String> reasons, EnumSet<JourneyStage> stages) {}

    private record ModelKey(UUID operationId, int attempt) {}

    private record ModelStart(ModelCallStarted event, long sequence) {}

    private record ModelTerminal(ModelTurn event, long sequence) {}

    private record ModelFailure(UUID operationId, long sequence) {}

    private record ToolKey(UUID operationId, String callId) {}

    private record SequencedToolCall(ToolCall event, long sequence) {}

    private record SequencedToolObservation(ToolObservation event, long sequence) {}

    private record RawCallKey(UUID operationId, int attempt, String callId) {}

    private record RawCall(RawCallKey key, String toolName, long sequence, int ordinal) {}

    private record TypedCall(ToolKey key, UUID parentOperationId, String toolName, long sequence) {}

    private record PublicationKey(UUID operationId, UUID parentOperationId, PublicationChannel channel) {}

    private record SequencedPublication(Publication event, long sequence) {}

    private record SequencedTerminal(BindingOrFailure event, long sequence) {}

    private record WorkScope(
            JourneyStage stage, UUID operationId, AgentTraceEvent.ResourceRef resource) {
        private static WorkScope from(AgentTraceEvent event) {
            return new WorkScope(
                    event.context().stage(), event.context().operationId(), event.context().resource());
        }
    }
}
