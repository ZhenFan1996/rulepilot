package com.rulepilot.agenttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisPrivateAgentTraceStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(30));

    @Test
    void atomicallyAppendsEncryptedEventsWithFixedExpiryAndRecoverableBindings() throws Exception {
        LettuceConnectionFactory connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        String prefix = "test:private-agent-trace:" + UUID.randomUUID() + ":";
        PrivateAgentTraceProperties properties = properties(prefix);
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 19);
        var store = new RedisPrivateAgentTraceStore(
                redis,
                new ObjectMapper().findAndRegisterModules(),
                new AesGcmAgentTracePayloadCipher(key, (short) 1, new java.security.SecureRandom()),
                properties);
        UUID traceId = UUID.randomUUID();
        Instant now = Instant.now();
        String owner = "private-trace-owner-sentinel-alice-20260824";
        String rawOwnerDigest = sha256(owner);
        String enumerableOwnerDigest = sha256("rulepilot-private-agent-trace-owner-v1\u0000" + owner);
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        PrivateAgentTraceStore.TraceSession created =
                store.create(traceId, owner, "session-digest", now, now.plusSeconds(120), now.plusSeconds(600));

        assertThat(created.ownerIdentity()).matches("[0-9a-f]{64}").doesNotContain(owner);
        assertThat(store.matchesOwner(created, owner)).isTrue();
        assertThat(store.matchesOwner(created, "mallory")).isFalse();

        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> appends = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                int position = index;
                appends.add(() -> store.append(
                                traceId,
                                new UserTurn(
                                        TraceEventContext.create(
                                                now,
                                                JourneyStage.ANSWER,
                                                UUID.randomUUID(),
                                                null,
                                                run),
                                        "private-sentinel-turn-" + position,
                                        "{\"position\":" + position + "}",
                                        "en"),
                                now.plusMillis(position))
                        .sequence());
            }
            List<Long> sequences = executor.invokeAll(appends).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .sorted()
                    .toList();

            assertThat(sequences).containsExactlyElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, 12).boxed().toList());
            PrivateAgentTraceStore.TraceReadResult read = store.read(traceId);
            assertThat(read.events()).hasSize(12);
            assertThat(read.events()).extracting(PrivateAgentTraceStore.StoredEvent::sequence)
                    .containsExactlyElementsOf(
                            java.util.stream.LongStream.rangeClosed(1, 12).boxed().toList());
            assertThat(read.problemCodes()).isEmpty();
            assertThat(read.observedEventCount()).isEqualTo(12);
            assertThat(read.observedStoredBytes()).isEqualTo(read.session().storedBytes());
            assertThat(store.find(traceId).orElseThrow().eventCount()).isEqualTo(12);
            assertThat(store.bind(traceId, run, now.plusSeconds(1))).isTrue();
            assertThat(store.resolve(run)).contains(traceId);

            UUID rejectedTrace = UUID.randomUUID();
            assertThatThrownBy(() -> store.create(
                            rejectedTrace,
                            owner,
                            "another-session-digest",
                            now,
                            now.plusSeconds(120),
                            now.plusSeconds(600)))
                    .isInstanceOfSatisfying(AgentTraceStoreException.class, exception ->
                            assertThat(exception.reason())
                                    .isEqualTo(AgentTraceStoreException.Reason.OWNER_QUOTA_REACHED));
            assertThat(redis.keys(prefix + "owner:*")).singleElement().satisfies(keyName -> {
                assertThat(keyName).doesNotContain(owner);
                assertThat(keyName.substring((prefix + "owner:").length())).matches("[0-9a-f]{64}");
                assertThat(keyName)
                        .doesNotEndWith(rawOwnerDigest)
                        .doesNotEndWith(enumerableOwnerDigest);
            });

            for (String redisKey : redis.keys(prefix + "*")) {
                Long ttl = redis.getExpire(redisKey, TimeUnit.MILLISECONDS);
                assertThat(ttl).isPositive().isLessThanOrEqualTo(600_000);
                assertThat(redisKey).doesNotContain(owner, rawOwnerDigest, enumerableOwnerDigest);
                assertThat(rawValues(redis, redisKey))
                        .noneMatch(value -> value.contains("private-sentinel-turn"))
                        .noneMatch(value -> value.contains(owner))
                        .noneMatch(value -> value.contains(rawOwnerDigest))
                        .noneMatch(value -> value.contains(enumerableOwnerDigest));
            }
            String metaKey = prefix + "{" + traceId + "}:meta";
            assertThat(redis.opsForHash().hasKey(metaKey, "ownerUsername")).isFalse();
            assertThat(redis.opsForHash().get(metaKey, "ownerIdentity"))
                    .hasToString(created.ownerIdentity());

            assertThatThrownBy(() -> store.delete(traceId, "mallory"))
                    .isInstanceOfSatisfying(AgentTraceStoreException.class, exception ->
                            assertThat(exception.reason()).isEqualTo(AgentTraceStoreException.Reason.NOT_FOUND));
            assertThat(store.resolve(run)).contains(traceId);

            store.seal(traceId, now.plusSeconds(2));
            assertThat(store.read(traceId).problemCodes()).isEmpty();
            assertThatThrownBy(() -> store.append(
                            traceId,
                            new UserTurn(
                                    TraceEventContext.create(
                                            now,
                                            JourneyStage.ANSWER,
                                            UUID.randomUUID(),
                                            null,
                                            run),
                                    "after seal",
                                    "{}",
                                    "en"),
                            now.plusSeconds(3)))
                    .isInstanceOfSatisfying(AgentTraceStoreException.class, exception ->
                            assertThat(exception.reason()).isEqualTo(AgentTraceStoreException.Reason.SEALED));
            PrivateAgentTraceStore.TraceReadResult rejectedLateWrite = store.read(traceId);
            assertThat(rejectedLateWrite.session().state()).isEqualTo(PrivateAgentTraceStore.TraceState.SEALED);
            assertThat(rejectedLateWrite.session().integrity())
                    .isEqualTo(PrivateAgentTraceStore.TraceIntegrity.INCOMPLETE);
            assertThat(rejectedLateWrite.session().incompleteReason()).isEqualTo("LATE_EVENT_AFTER_SEAL");
            assertThat(rejectedLateWrite.problemCodes()).isEmpty();
            assertOwnerMaterialAbsent(redis, prefix, owner, rawOwnerDigest, enumerableOwnerDigest);

            AgentTraceExporter.PreparedExport exported = new AgentTraceExporter(
                            new ObjectMapper().findAndRegisterModules())
                    .prepare(new PrivateAgentTraceService.ExportSnapshot(
                            rejectedLateWrite.session(), rejectedLateWrite));
            assertThat(new String(exported.manifestJson(), StandardCharsets.UTF_8))
                    .doesNotContain(owner, rawOwnerDigest, enumerableOwnerDigest);

            store.delete(traceId, owner);
            assertThat(store.find(traceId)).isEmpty();
            assertThat(store.resolve(run)).isEmpty();
            assertThat(redis.keys(prefix + "*")).isEmpty();

            UUID replacement = UUID.randomUUID();
            PrivateAgentTraceStore.TraceSession replacementSession = store.create(
                    replacement,
                    owner,
                    "replacement-session-digest",
                    now,
                    now.plusSeconds(120),
                    now.plusSeconds(600));
            assertThat(replacementSession.traceId()).isEqualTo(replacement);
            assertThat(replacementSession.ownerIdentity()).isEqualTo(created.ownerIdentity());
            store.delete(replacement, owner);
        } finally {
            executor.shutdownNow();
            connections.destroy();
        }
    }

    @Test
    void keepsOwnerAuthorizationPrivateAcrossRecoverSealExportAndDelete() throws Exception {
        LettuceConnectionFactory connections = connections();
        StringRedisTemplate redis = redis(connections);
        String prefix = "test:private-agent-trace:" + UUID.randomUUID() + ":";
        PrivateAgentTraceProperties properties = properties(prefix);
        String owner = "private-owner-control-plane-sentinel-20260824";
        properties.setAllowedUsers(List.of(owner));
        RedisPrivateAgentTraceStore store = store(redis, properties);
        Instant now = Instant.now();
        PrivateAgentTraceService service =
                new PrivateAgentTraceService(store, properties, Clock.fixed(now, ZoneOffset.UTC));
        Principal principal = () -> owner;
        Principal wrongOwner = () -> "mallory";
        MockHttpSession session = new MockHttpSession();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        String rawOwnerDigest = sha256(owner);
        String enumerableOwnerDigest = sha256("rulepilot-private-agent-trace-owner-v1\u0000" + owner);
        try {
            service.start(principal, session);
            CaptureHandle capture = service.current(principal, session);
            assertThat(capture.bind(run)).isTrue();
            assertThat(service.recover(run, owner).enabled()).isTrue();
            assertThat(service.recover(run, wrongOwner.getName()).enabled()).isFalse();

            assertThatThrownBy(() -> service.status(wrongOwner, session))
                    .isInstanceOfSatisfying(PrivateAgentTraceService.TraceAccessException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(PrivateAgentTraceService.AccessCode.TRACE_NOT_FOUND));
            assertThatThrownBy(() -> service.seal(wrongOwner, session))
                    .isInstanceOfSatisfying(PrivateAgentTraceService.TraceAccessException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(PrivateAgentTraceService.AccessCode.TRACE_NOT_FOUND));
            assertThatThrownBy(() -> service.beginExport(wrongOwner, session))
                    .isInstanceOfSatisfying(PrivateAgentTraceService.TraceAccessException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(PrivateAgentTraceService.AccessCode.TRACE_NOT_FOUND));
            assertThatThrownBy(() -> service.delete(wrongOwner, session))
                    .isInstanceOfSatisfying(PrivateAgentTraceService.TraceAccessException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(PrivateAgentTraceService.AccessCode.TRACE_NOT_FOUND));

            service.seal(principal, session);
            try (PrivateAgentTraceService.ExportLease export = service.beginExport(principal, session)) {
                AgentTraceExporter.PreparedExport artifact = new AgentTraceExporter(
                                new ObjectMapper().findAndRegisterModules())
                        .prepare(export.snapshot());
                assertThat(new String(artifact.manifestJson(), StandardCharsets.UTF_8))
                        .doesNotContain(owner, rawOwnerDigest, enumerableOwnerDigest);
            }
            assertOwnerMaterialAbsent(redis, prefix, owner, rawOwnerDigest, enumerableOwnerDigest);

            service.delete(principal, session);
            assertThat(redis.keys(prefix + "*")).isEmpty();
        } finally {
            connections.destroy();
        }
    }

    @Test
    void atomicallyMarksAnExpiredLateAppendIncompleteAndProducesAVerifiableSeal() {
        LettuceConnectionFactory connections = connections();
        StringRedisTemplate redis = redis(connections);
        String prefix = "test:private-agent-trace:" + UUID.randomUUID() + ":";
        RedisPrivateAgentTraceStore store = store(redis, prefix);
        UUID traceId = UUID.randomUUID();
        Instant now = Instant.now();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        store.create(traceId, "alice", "session-digest", now, now.plusSeconds(1), now.plusSeconds(600));
        try {
            assertThatThrownBy(() -> store.append(
                            traceId,
                            userTurn(now, run, "after capture deadline"),
                            now.plusSeconds(2)))
                    .isInstanceOfSatisfying(AgentTraceStoreException.class, exception ->
                            assertThat(exception.reason()).isEqualTo(AgentTraceStoreException.Reason.EXPIRED));

            PrivateAgentTraceStore.TraceReadResult read = store.read(traceId);
            assertThat(read.session().state()).isEqualTo(PrivateAgentTraceStore.TraceState.SEALED);
            assertThat(read.session().integrity()).isEqualTo(PrivateAgentTraceStore.TraceIntegrity.INCOMPLETE);
            assertThat(read.session().incompleteReason()).isEqualTo("LATE_EVENT_AFTER_CAPTURE_DEADLINE");
            assertThat(read.session().eventCount()).isZero();
            assertThat(read.problemCodes()).isEmpty();
        } finally {
            store.delete(traceId, "alice");
            connections.destroy();
        }
    }

    @Test
    void serializesSealAgainstAnInFlightAppendWithoutAFalseCompleteArtifact() throws Exception {
        LettuceConnectionFactory connections = connections();
        StringRedisTemplate redis = redis(connections);
        String prefix = "test:private-agent-trace:" + UUID.randomUUID() + ":";
        RedisPrivateAgentTraceStore store = store(redis, prefix);
        UUID traceId = UUID.randomUUID();
        Instant now = Instant.now();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        store.create(traceId, "alice", "session-digest", now, now.plusSeconds(120), now.plusSeconds(600));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var append = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    store.append(traceId, userTurn(now, run, "racing append"), now.plusSeconds(1));
                    return true;
                } catch (AgentTraceStoreException exception) {
                    assertThat(exception.reason()).isEqualTo(AgentTraceStoreException.Reason.SEALED);
                    return false;
                }
            });
            var seal = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.seal(traceId, now.plusSeconds(1));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean appendWon = append.get(10, TimeUnit.SECONDS);
            seal.get(10, TimeUnit.SECONDS);

            PrivateAgentTraceStore.TraceReadResult read = store.read(traceId);
            assertThat(read.session().state()).isEqualTo(PrivateAgentTraceStore.TraceState.SEALED);
            assertThat(read.problemCodes()).isEmpty();
            if (appendWon) {
                assertThat(read.session().eventCount()).isOne();
                assertThat(read.session().integrity()).isEqualTo(PrivateAgentTraceStore.TraceIntegrity.COMPLETE);
            } else {
                assertThat(read.session().eventCount()).isZero();
                assertThat(read.session().integrity()).isEqualTo(PrivateAgentTraceStore.TraceIntegrity.INCOMPLETE);
                assertThat(read.session().incompleteReason()).isEqualTo("LATE_EVENT_AFTER_SEAL");
            }
        } finally {
            executor.shutdownNow();
            store.delete(traceId, "alice");
            connections.destroy();
        }
    }

    @Test
    void detectsReorderedEncryptedEvents() {
        try (RedisFixture fixture = fixtureWithEvents()) {
            List<String> values = fixture.redis().opsForList().range(fixture.eventsKey(), 0, -1);
            assertThat(values).hasSize(3);
            fixture.redis().opsForList().set(fixture.eventsKey(), 0, values.get(1));
            fixture.redis().opsForList().set(fixture.eventsKey(), 1, values.get(0));

            assertThat(fixture.store().read(fixture.traceId()).problemCodes())
                    .anyMatch(code -> code.startsWith("SEQUENCE_MISMATCH_AT_"))
                    .anyMatch(code -> code.startsWith("CHAIN_PREDECESSOR_MISMATCH_AT_"));
        }
    }

    @Test
    void detectsDeletedEncryptedEventsAgainstAuthoritativeMetadata() {
        try (RedisFixture fixture = fixtureWithEvents()) {
            fixture.redis().opsForList().leftPop(fixture.eventsKey());

            assertThat(fixture.store().read(fixture.traceId()).problemCodes())
                    .contains("EVENT_COUNT_MISMATCH", "STORED_BYTES_MISMATCH")
                    .anyMatch(code -> code.startsWith("SEQUENCE_MISMATCH_AT_"));
        }
    }

    @Test
    void detectsReplayedEncryptedEvents() {
        try (RedisFixture fixture = fixtureWithEvents()) {
            String first = fixture.redis().opsForList().index(fixture.eventsKey(), 0);
            assertThat(first).isNotNull();
            fixture.redis().opsForList().rightPush(fixture.eventsKey(), first);

            assertThat(fixture.store().read(fixture.traceId()).problemCodes())
                    .contains("EVENT_COUNT_MISMATCH", "STORED_BYTES_MISMATCH", "CHAIN_HEAD_MISMATCH")
                    .anyMatch(code -> code.startsWith("SEQUENCE_MISMATCH_AT_"));
        }
    }

    @Test
    void sealedProofDetectsCoordinatedTailTruncationAndPlaintextMetadataRewrite() throws Exception {
        try (RedisFixture fixture = fixtureWithEvents()) {
            fixture.store().seal(fixture.traceId(), Instant.now());
            List<String> values = fixture.redis().opsForList().range(fixture.eventsKey(), 0, -1);
            assertThat(values).hasSize(3);
            fixture.redis().opsForList().rightPop(fixture.eventsKey());
            long rewrittenBytes = values.subList(0, 2).stream()
                    .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
            String rewrittenHead = new ObjectMapper()
                    .readTree(values.get(1))
                    .path("chainCommitment")
                    .asText();
            String metaKey = fixture.eventsKey().replace(":events", ":meta");
            fixture.redis().opsForHash().put(metaKey, "eventCount", "2");
            fixture.redis().opsForHash().put(metaKey, "storedBytes", Long.toString(rewrittenBytes));
            fixture.redis().opsForHash().put(metaKey, "chainHead", rewrittenHead);

            PrivateAgentTraceStore.TraceReadResult read = fixture.store().read(fixture.traceId());
            assertThat(read.problemCodes()).containsExactly("SEAL_PROOF_INVALID");
            assertThat(read.events()).hasSize(2);
        }
    }

    @Test
    void atomicallyClaimsOneRetainedTraceSlotForConcurrentSessionsOfTheSameOwner() throws Exception {
        LettuceConnectionFactory connections = connections();
        StringRedisTemplate redis = redis(connections);
        String prefix = "test:private-agent-trace:" + UUID.randomUUID() + ":";
        RedisPrivateAgentTraceStore store = store(redis, prefix);
        Instant now = Instant.now();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<UUID>> creates = List.of(
                    () -> createOrNull(store, first, "session-one", now),
                    () -> createOrNull(store, second, "session-two", now));
            List<UUID> retained = executor.invokeAll(creates).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(retained).hasSize(1);
            assertThat(redis.keys(prefix + "owner:*")).hasSize(1);
            UUID winner = retained.getFirst();
            store.delete(winner, "same-owner");
            UUID replacement = UUID.randomUUID();
            assertThat(createOrNull(store, replacement, "session-three", now)).isEqualTo(replacement);
            store.delete(replacement, "same-owner");
        } finally {
            executor.shutdownNow();
            connections.destroy();
        }
    }

    private UUID createOrNull(
            RedisPrivateAgentTraceStore store, UUID traceId, String sessionDigest, Instant now) {
        try {
            return store.create(
                            traceId,
                            "same-owner",
                            sessionDigest,
                            now,
                            now.plusSeconds(120),
                            now.plusSeconds(600))
                    .traceId();
        } catch (AgentTraceStoreException exception) {
            assertThat(exception.reason()).isEqualTo(AgentTraceStoreException.Reason.OWNER_QUOTA_REACHED);
            return null;
        }
    }

    private RedisFixture fixtureWithEvents() {
        LettuceConnectionFactory connections = connections();
        StringRedisTemplate redis = redis(connections);
        String prefix = "test:private-agent-trace:" + UUID.randomUUID() + ":";
        RedisPrivateAgentTraceStore store = store(redis, prefix);
        UUID traceId = UUID.randomUUID();
        Instant now = Instant.now();
        ResourceRef run = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());
        store.create(traceId, "alice", "session-digest", now, now.plusSeconds(120), now.plusSeconds(600));
        for (int index = 0; index < 3; index++) {
            store.append(
                    traceId,
                    new UserTurn(
                            TraceEventContext.create(now, JourneyStage.ANSWER, UUID.randomUUID(), null, run),
                            "private-tamper-sentinel-" + index,
                            "{\"position\":" + index + "}",
                            "en"),
                    now.plusMillis(index));
        }
        return new RedisFixture(connections, redis, store, traceId, prefix + "{" + traceId + "}:events");
    }

    private LettuceConnectionFactory connections() {
        LettuceConnectionFactory connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        return connections;
    }

    private StringRedisTemplate redis(LettuceConnectionFactory connections) {
        StringRedisTemplate redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        return redis;
    }

    private RedisPrivateAgentTraceStore store(StringRedisTemplate redis, String prefix) {
        return store(redis, properties(prefix));
    }

    private RedisPrivateAgentTraceStore store(
            StringRedisTemplate redis, PrivateAgentTraceProperties properties) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 19);
        return new RedisPrivateAgentTraceStore(
                redis,
                new ObjectMapper().findAndRegisterModules(),
                new AesGcmAgentTracePayloadCipher(key, (short) 1, new java.security.SecureRandom()),
                properties);
    }

    private PrivateAgentTraceProperties properties(String prefix) {
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setRedisPrefix(prefix);
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }

    private UserTurn userTurn(Instant occurredAt, ResourceRef resource, String text) {
        return new UserTurn(
                TraceEventContext.create(
                        occurredAt,
                        JourneyStage.ANSWER,
                        UUID.randomUUID(),
                        null,
                        resource),
                text,
                "{}",
                "en");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertOwnerMaterialAbsent(
            StringRedisTemplate redis,
            String prefix,
            String owner,
            String rawOwnerDigest,
            String enumerableOwnerDigest) {
        for (String redisKey : redis.keys(prefix + "*")) {
            assertThat(redisKey).doesNotContain(owner, rawOwnerDigest, enumerableOwnerDigest);
            assertThat(rawValues(redis, redisKey))
                    .noneMatch(value -> value.contains(owner))
                    .noneMatch(value -> value.contains(rawOwnerDigest))
                    .noneMatch(value -> value.contains(enumerableOwnerDigest));
        }
    }

    private List<String> rawValues(StringRedisTemplate redis, String key) {
        DataType type = redis.type(key);
        if (type == DataType.STRING) {
            String value = redis.opsForValue().get(key);
            return value == null ? List.of() : List.of(value);
        }
        if (type == DataType.LIST) {
            List<String> values = redis.opsForList().range(key, 0, -1);
            return values == null ? List.of() : values;
        }
        if (type == DataType.SET) {
            var values = redis.opsForSet().members(key);
            return values == null ? List.of() : List.copyOf(values);
        }
        if (type == DataType.HASH) {
            return redis.opsForHash().values(key).stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private record RedisFixture(
            LettuceConnectionFactory connections,
            StringRedisTemplate redis,
            RedisPrivateAgentTraceStore store,
            UUID traceId,
            String eventsKey)
            implements AutoCloseable {
        @Override
        public void close() {
            try {
                store.delete(traceId, "alice");
            } catch (RuntimeException ignored) {
                // Test cleanup is bounded by the fixture's fixed Redis TTL.
            }
            connections.destroy();
        }
    }
}
