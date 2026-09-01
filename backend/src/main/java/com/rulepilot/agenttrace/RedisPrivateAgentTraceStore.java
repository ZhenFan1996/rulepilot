package com.rulepilot.agenttrace;

import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.CAP_REACHED;
import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.CORRUPT;
import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.EXPIRED;
import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.NOT_FOUND;
import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.OWNER_QUOTA_REACHED;
import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.SEALED;
import static com.rulepilot.agenttrace.AgentTraceStoreException.Reason.UNAVAILABLE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTracePayloadCipher.EncryptedPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

final class RedisPrivateAgentTraceStore implements PrivateAgentTraceStore {

    private static final int EVENT_SCHEMA_VERSION = 2;
    private static final int MAXIMUM_APPEND_ATTEMPTS = 64;
    private static final int MAXIMUM_READ_SNAPSHOT_ATTEMPTS = 5;
    private static final String CHAIN_GENESIS = sha256Hex("rulepilot-private-agent-trace-chain-v2:genesis");
    private static final byte[] SEAL_PLAINTEXT =
            "rulepilot-private-agent-trace-seal-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OWNER_DIGEST_DOMAIN =
            "rulepilot-private-agent-trace-owner-identity-v1".getBytes(StandardCharsets.UTF_8);

    private static final RedisScript<Long> CREATE = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) ~= 0 then return -1 end
            if redis.call('EXISTS', KEYS[2]) ~= 0 then return -2 end
            redis.call('HSET', KEYS[1],
              'ownerIdentity', ARGV[1],
              'sessionDigest', ARGV[2],
              'state', 'ACTIVE',
              'integrity', 'COMPLETE',
              'incompleteReason', '',
              'createdAt', ARGV[3],
              'captureUntil', ARGV[4],
              'expiresAt', ARGV[5],
              'sealedAt', '',
              'eventCount', '0',
              'storedBytes', '0',
              'chainHead', ARGV[6],
              'sealBindingDigest', '',
              'sealKeyVersion', '',
              'sealNonce', '',
              'sealCiphertext', '')
            redis.call('PEXPIREAT', KEYS[1], ARGV[5])
            redis.call('SET', KEYS[2], ARGV[7], 'PXAT', ARGV[5])
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> APPEND = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then
              if redis.call('HGET', KEYS[1], 'integrity') == 'COMPLETE' then
                redis.call('HSET', KEYS[1],
                  'integrity', 'INCOMPLETE',
                  'incompleteReason', 'LATE_EVENT_AFTER_SEAL',
                  'sealBindingDigest', '',
                  'sealKeyVersion', '',
                  'sealNonce', '',
                  'sealCiphertext', '')
              end
              return -2
            end
            local now = tonumber(ARGV[1])
            local captureUntil = tonumber(redis.call('HGET', KEYS[1], 'captureUntil'))
            if now >= captureUntil then
              if redis.call('HGET', KEYS[1], 'integrity') == 'COMPLETE' then
                redis.call('HSET', KEYS[1],
                  'integrity', 'INCOMPLETE',
                  'incompleteReason', 'LATE_EVENT_AFTER_CAPTURE_DEADLINE')
              end
              redis.call('HSET', KEYS[1],
                'state', 'SEALED',
                'sealedAt', ARGV[1],
                'sealBindingDigest', '',
                'sealKeyVersion', '',
                'sealNonce', '',
                'sealCiphertext', '')
              return -3
            end
            local count = tonumber(redis.call('HGET', KEYS[1], 'eventCount') or '-1')
            local physicalCount = redis.call('LLEN', KEYS[2])
            if count < 0 or count ~= physicalCount then return -6 end
            if count ~= tonumber(ARGV[2]) or redis.call('HGET', KEYS[1], 'chainHead') ~= ARGV[3] then
              return -5
            end
            local eventBytes = tonumber(ARGV[4])
            local maximumBytes = tonumber(ARGV[5])
            local usedBytes = tonumber(redis.call('HGET', KEYS[1], 'storedBytes') or '-1')
            if usedBytes < 0 then return -6 end
            if usedBytes + eventBytes > maximumBytes then
              redis.call('HSET', KEYS[1],
                'state', 'SEALED',
                'integrity', 'TRUNCATED',
                'incompleteReason', 'TRACE_CAP_REACHED',
                'sealedAt', ARGV[1])
              return -4
            end
            local sequence = redis.call('RPUSH', KEYS[2], ARGV[6])
            if sequence ~= count + 1 then return -6 end
            redis.call('HSET', KEYS[1],
              'storedBytes', usedBytes + eventBytes,
              'eventCount', sequence,
              'chainHead', ARGV[7])
            redis.call('PEXPIREAT', KEYS[2], ARGV[8])
            return sequence
            """,
            Long.class);

    private static final RedisScript<Long> BEGIN_SEAL = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'state') == 'SEALED' then
              local sealedAt = redis.call('HGET', KEYS[1], 'sealedAt')
              if not sealedAt or sealedAt == '' then return -2 end
              return 0
            end
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return -2 end
            redis.call('HSET', KEYS[1], 'state', 'SEALED', 'sealedAt', ARGV[1])
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> PROVE_SEAL = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'SEALED' then return -2 end
            local existingProof = redis.call('HGET', KEYS[1], 'sealCiphertext')
            if existingProof and existingProof ~= '' then return 0 end
            if redis.call('HGET', KEYS[1], 'ownerIdentity') ~= ARGV[1]
                or redis.call('HGET', KEYS[1], 'sessionDigest') ~= ARGV[2]
                or redis.call('HGET', KEYS[1], 'integrity') ~= ARGV[3]
                or redis.call('HGET', KEYS[1], 'incompleteReason') ~= ARGV[4]
                or redis.call('HGET', KEYS[1], 'createdAt') ~= ARGV[5]
                or redis.call('HGET', KEYS[1], 'captureUntil') ~= ARGV[6]
                or redis.call('HGET', KEYS[1], 'expiresAt') ~= ARGV[7]
                or redis.call('HGET', KEYS[1], 'sealedAt') ~= ARGV[8]
                or redis.call('HGET', KEYS[1], 'eventCount') ~= ARGV[9]
                or redis.call('HGET', KEYS[1], 'storedBytes') ~= ARGV[10]
                or redis.call('HGET', KEYS[1], 'chainHead') ~= ARGV[11] then return -2 end
            local bindingCount = tonumber(ARGV[12])
            if not bindingCount or bindingCount < 0 or #KEYS ~= bindingCount + 2 then return -2 end
            if redis.call('SCARD', KEYS[2]) ~= bindingCount then return -2 end
            for index = 1, bindingCount do
              local resourceKey = KEYS[index + 2]
              if redis.call('SISMEMBER', KEYS[2], resourceKey) ~= 1 then return -2 end
              local expectedValue = ARGV[index + 16]
              local currentValue = redis.call('GET', resourceKey)
              if expectedValue == '' then
                if currentValue then return -2 end
              elseif currentValue ~= expectedValue then
                return -2
              end
            end
            redis.call('HSET', KEYS[1],
              'sealBindingDigest', ARGV[13],
              'sealKeyVersion', ARGV[14],
              'sealNonce', ARGV[15],
              'sealCiphertext', ARGV[16])
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> MARK_INCOMPLETE = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'integrity') == 'TRUNCATED' then return 0 end
            if redis.call('HGET', KEYS[1], 'integrity') == 'INCOMPLETE' then return 0 end
            redis.call('HSET', KEYS[1],
              'integrity', 'INCOMPLETE',
              'incompleteReason', ARGV[1],
              'sealBindingDigest', '',
              'sealKeyVersion', '',
              'sealNonce', '',
              'sealCiphertext', '')
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> MARK_TRUNCATED = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            redis.call('HSET', KEYS[1],
              'state', 'SEALED',
              'integrity', 'TRUNCATED',
              'incompleteReason', ARGV[1],
              'sealedAt', ARGV[2])
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> BIND = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return 0 end
            if tonumber(ARGV[1]) >= tonumber(redis.call('HGET', KEYS[1], 'captureUntil')) then return 0 end
            local existing = redis.call('GET', KEYS[2])
            if existing and existing ~= ARGV[2] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PXAT', ARGV[3])
            redis.call('SADD', KEYS[3], KEYS[2])
            redis.call('PEXPIREAT', KEYS[3], ARGV[3])
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> DELETE = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              if redis.call('EXISTS', KEYS[2]) ~= 0
                  or redis.call('EXISTS', KEYS[3]) ~= 0
                  or redis.call('GET', KEYS[4]) == ARGV[2] then return -4 end
              return 0
            end
            if redis.call('HGET', KEYS[1], 'ownerIdentity') ~= ARGV[1] then return -2 end
            if redis.call('GET', KEYS[4]) ~= ARGV[2] then return -3 end
            local resourceKeys = redis.call('SMEMBERS', KEYS[3])
            for _, resourceKey in ipairs(resourceKeys) do
              if string.sub(resourceKey, 1, string.len(ARGV[3])) ~= ARGV[3] then return -4 end
            end
            for _, resourceKey in ipairs(resourceKeys) do
              if redis.call('GET', resourceKey) == ARGV[2] then redis.call('DEL', resourceKey) end
            end
            redis.call('DEL', KEYS[2], KEYS[3], KEYS[1])
            if redis.call('GET', KEYS[4]) == ARGV[2] then redis.call('DEL', KEYS[4]) end
            if redis.call('EXISTS', KEYS[1]) ~= 0
                or redis.call('EXISTS', KEYS[2]) ~= 0
                or redis.call('EXISTS', KEYS[3]) ~= 0
                or redis.call('GET', KEYS[4]) == ARGV[2] then return -5 end
            return 1
            """,
            Long.class);

    private static final RedisScript<Long> DELETE_IF_VALUE = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentTracePayloadCipher cipher;
    private final long maximumBytes;
    private final long maximumEventBytes;
    private final String prefix;

    RedisPrivateAgentTraceStore(
            StringRedisTemplate redis,
            ObjectMapper json,
            AgentTracePayloadCipher cipher,
            PrivateAgentTraceProperties properties) {
        if (redis == null || json == null || cipher == null || properties == null) {
            throw new IllegalArgumentException("private agent trace store dependencies are required");
        }
        properties.validate();
        this.redis = redis;
        this.json = json;
        this.cipher = cipher;
        this.maximumBytes = properties.getMaxBytes().toBytes();
        this.maximumEventBytes = properties.getMaxEventBytes().toBytes();
        this.prefix = properties.getRedisPrefix();
    }

    @Override
    public boolean available() {
        return cipher.available();
    }

    @Override
    public TraceSession create(
            UUID traceId,
            String ownerUsername,
            String sessionDigest,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt) {
        if (!available()) throw new AgentTraceStoreException(UNAVAILABLE);
        requireIdentity(traceId, ownerUsername, sessionDigest, createdAt, captureUntil, expiresAt);
        String ownerIdentity = ownerIdentity(ownerUsername);
        try {
            Long result = redis.execute(
                    CREATE,
                    List.of(metaKey(traceId), ownerSlotKey(ownerIdentity)),
                    ownerIdentity,
                    sessionDigest,
                    epoch(createdAt),
                    epoch(captureUntil),
                    epoch(expiresAt),
                    CHAIN_GENESIS,
                    traceId.toString());
            if (result == null) throw new AgentTraceStoreException(UNAVAILABLE);
            if (result == -1) throw new AgentTraceStoreException(CORRUPT);
            if (result == -2) throw new AgentTraceStoreException(OWNER_QUOTA_REACHED);
            if (result != 1) throw new AgentTraceStoreException(CORRUPT);
            return find(traceId).orElseThrow(() -> new AgentTraceStoreException(UNAVAILABLE));
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            bestEffortCleanup(traceId, ownerUsername);
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    @Override
    public Optional<TraceSession> find(UUID traceId) {
        return findRecord(traceId).map(TraceRecord::session);
    }

    @Override
    public boolean matchesOwner(TraceSession trace, String ownerUsername) {
        if (trace == null) return false;
        try {
            return MessageDigest.isEqual(
                    trace.ownerIdentity().getBytes(StandardCharsets.UTF_8),
                    ownerIdentity(ownerUsername).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    @Override
    public TraceSession seal(UUID traceId, Instant sealedAt) {
        if (traceId == null || sealedAt == null) throw new IllegalArgumentException("trace seal identity is required");
        try {
            Long begun = redis.execute(BEGIN_SEAL, List.of(metaKey(traceId)), epoch(sealedAt));
            if (begun == null) throw new AgentTraceStoreException(UNAVAILABLE);
            if (begun == -1) throw new AgentTraceStoreException(NOT_FOUND);
            if (begun == -2 || begun < -2 || begun > 1) throw new AgentTraceStoreException(CORRUPT);
            for (int attempt = 0; attempt < MAXIMUM_READ_SNAPSHOT_ATTEMPTS; attempt++) {
                TraceReadResult read = read(traceId);
                boolean invalidBinding = read.problemCodes().stream().anyMatch(this::bindingProblem);
                if (invalidBinding && read.session().integrity() == TraceIntegrity.COMPLETE) {
                    markIncomplete(traceId, "RESOURCE_BINDING_INVALID");
                    continue;
                }
                if (read.problemCodes().stream()
                        .anyMatch(code -> !"SEAL_PROOF_MISSING".equals(code) && !bindingProblem(code))) {
                    throw new AgentTraceStoreException(CORRUPT);
                }
                TraceRecord trace = findRecord(traceId).orElseThrow(() -> new AgentTraceStoreException(NOT_FOUND));
                if (!trace.session().equals(read.session())) continue;
                if (trace.sealProof().isPresent()) {
                    if (read.problemCodes().isEmpty()) return trace.session();
                    throw new AgentTraceStoreException(CORRUPT);
                }
                BindingSnapshot bindings = bindingSnapshot(traceId);
                EncryptedPayload proof = cipher.encrypt(
                        sealAssociatedData(traceId, trace.session(), trace.chainHead(), bindings.digest()),
                        SEAL_PLAINTEXT);
                List<String> proofKeys = new ArrayList<>(bindings.records().size() + 2);
                proofKeys.add(metaKey(traceId));
                proofKeys.add(bindingsKey(traceId));
                bindings.records().stream().map(BindingRecord::resourceKey).forEach(proofKeys::add);
                List<String> proofArguments = new ArrayList<>(bindings.records().size() + 16);
                proofArguments.add(trace.session().ownerIdentity());
                proofArguments.add(trace.session().sessionDigest());
                proofArguments.add(trace.session().integrity().name());
                proofArguments.add(trace.session().incompleteReason());
                proofArguments.add(epoch(trace.session().createdAt()));
                proofArguments.add(epoch(trace.session().captureUntil()));
                proofArguments.add(epoch(trace.session().expiresAt()));
                proofArguments.add(epoch(trace.session().sealedAt()));
                proofArguments.add(Long.toString(trace.session().eventCount()));
                proofArguments.add(Long.toString(trace.session().storedBytes()));
                proofArguments.add(trace.chainHead());
                proofArguments.add(Integer.toString(bindings.records().size()));
                proofArguments.add(bindings.digest());
                proofArguments.add(Short.toString(proof.keyVersion()));
                proofArguments.add(Base64.getEncoder().encodeToString(proof.nonce()));
                proofArguments.add(Base64.getEncoder().encodeToString(proof.ciphertext()));
                bindings.records().stream().map(BindingRecord::traceReference).forEach(proofArguments::add);
                Long proved = redis.execute(
                        PROVE_SEAL,
                        proofKeys,
                        proofArguments.toArray(String[]::new));
                if (proved == null) throw new AgentTraceStoreException(UNAVAILABLE);
                if (proved == -1) throw new AgentTraceStoreException(NOT_FOUND);
                if (proved == -2) continue;
                if (proved == 0) continue;
                if (proved != 1) throw new AgentTraceStoreException(CORRUPT);
                return find(traceId).orElseThrow(() -> new AgentTraceStoreException(NOT_FOUND));
            }
            throw new AgentTraceStoreException(UNAVAILABLE);
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    @Override
    public void delete(UUID traceId, String ownerUsername) {
        if (traceId == null) throw new IllegalArgumentException("trace id is required");
        String owner = checkedOwner(ownerUsername);
        String ownerIdentity = ownerIdentity(owner);
        try {
            Long result = redis.execute(
                    DELETE,
                    List.of(metaKey(traceId), eventsKey(traceId), bindingsKey(traceId), ownerSlotKey(ownerIdentity)),
                    ownerIdentity,
                    traceId.toString(),
                    resourcePrefix());
            if (result == null) throw new AgentTraceStoreException(UNAVAILABLE);
            if (result == -2) throw new AgentTraceStoreException(NOT_FOUND);
            if (result == -3 || result == -4 || result == -5) throw new AgentTraceStoreException(CORRUPT);
            if (result != 0 && result != 1) throw new AgentTraceStoreException(CORRUPT);
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    @Override
    public AppendResult append(UUID traceId, AgentTraceEvent event, Instant appendedAt) {
        if (traceId == null || event == null || appendedAt == null) {
            throw new IllegalArgumentException("private agent trace append is invalid");
        }
        if (!available()) throw new AgentTraceStoreException(UNAVAILABLE);
        byte[] plaintext;
        try {
            plaintext = json.writeValueAsBytes(event);
        } catch (JsonProcessingException exception) {
            markIncomplete(traceId, "EVENT_SERIALIZATION_FAILED");
            throw new AgentTraceStoreException(CORRUPT, exception);
        }
        for (int attempt = 0; attempt < MAXIMUM_APPEND_ATTEMPTS; attempt++) {
            TraceRecord trace = findRecord(traceId).orElseThrow(() -> new AgentTraceStoreException(NOT_FOUND));
            long sequence = trace.session().eventCount() + 1;
            EncryptedRecord record = record(traceId, sequence, trace.chainHead(), event, plaintext);
            if (record.eventBytes() > maximumEventBytes) {
                markTruncated(traceId, "EVENT_CAP_REACHED", appendedAt);
                throw new AgentTraceStoreException(CAP_REACHED);
            }
            try {
                Long result = redis.execute(
                        APPEND,
                        List.of(metaKey(traceId), eventsKey(traceId)),
                        epoch(appendedAt),
                        Long.toString(sequence - 1),
                        trace.chainHead(),
                        Long.toString(record.eventBytes()),
                        Long.toString(maximumBytes),
                        record.encoded(),
                        record.chainCommitment(),
                        epoch(trace.session().expiresAt()));
                if (result == null) throw new AgentTraceStoreException(UNAVAILABLE);
                if (result == -1) throw new AgentTraceStoreException(NOT_FOUND);
                if (result == -2) {
                    bestEffortProveRejectedAppend(traceId, appendedAt);
                    throw new AgentTraceStoreException(SEALED);
                }
                if (result == -3) {
                    bestEffortProveRejectedAppend(traceId, appendedAt);
                    throw new AgentTraceStoreException(EXPIRED);
                }
                if (result == -4) throw new AgentTraceStoreException(CAP_REACHED);
                if (result == -5) continue;
                if (result == -6 || result < 1) throw new AgentTraceStoreException(CORRUPT);
                return new AppendResult(result, record.eventBytes());
            } catch (AgentTraceStoreException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new AgentTraceStoreException(UNAVAILABLE, exception);
            }
        }
        throw new AgentTraceStoreException(UNAVAILABLE);
    }

    @Override
    public void markIncomplete(UUID traceId, String reasonCode) {
        if (traceId == null) return;
        try {
            Long result = redis.execute(MARK_INCOMPLETE, List.of(metaKey(traceId)), reason(reasonCode));
            if (result == null) throw new AgentTraceStoreException(UNAVAILABLE);
            if (result < -1 || result > 1) throw new AgentTraceStoreException(CORRUPT);
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    @Override
    public boolean bind(UUID traceId, ResourceRef resource, Instant boundAt) {
        if (traceId == null || resource == null || boundAt == null) return false;
        TraceSession trace = find(traceId).orElseThrow(() -> new AgentTraceStoreException(NOT_FOUND));
        try {
            Long result = redis.execute(
                    BIND,
                    List.of(metaKey(traceId), resourceKey(resource), bindingsKey(traceId)),
                    epoch(boundAt),
                    traceId.toString(),
                    epoch(trace.expiresAt()));
            if (result == null) throw new AgentTraceStoreException(UNAVAILABLE);
            if (result == -1) throw new AgentTraceStoreException(NOT_FOUND);
            if (result != 0 && result != 1) throw new AgentTraceStoreException(CORRUPT);
            return result == 1;
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    @Override
    public Optional<UUID> resolve(ResourceRef resource) {
        if (resource == null) return Optional.empty();
        try {
            String key = resourceKey(resource);
            String value = redis.opsForValue().get(key);
            if (value == null || value.isBlank()) return Optional.empty();
            UUID traceId = UUID.fromString(value);
            if (!Boolean.TRUE.equals(redis.opsForSet().isMember(bindingsKey(traceId), key))) {
                throw new AgentTraceStoreException(CORRUPT);
            }
            return Optional.of(traceId);
        } catch (IllegalArgumentException exception) {
            throw new AgentTraceStoreException(CORRUPT, exception);
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    @Override
    public TraceReadResult read(UUID traceId) {
        if (traceId == null) throw new IllegalArgumentException("trace id is required");
        if (!available()) throw new AgentTraceStoreException(UNAVAILABLE);
        TraceRecord last = null;
        BindingSnapshot lastBindings = BindingSnapshot.empty();
        List<String> encoded = List.of();
        boolean stable = false;
        try {
            for (int attempt = 0; attempt < MAXIMUM_READ_SNAPSHOT_ATTEMPTS; attempt++) {
                TraceRecord before = findRecord(traceId).orElseThrow(() -> new AgentTraceStoreException(NOT_FOUND));
                List<String> candidate = redis.opsForList().range(eventsKey(traceId), 0, -1);
                BindingSnapshot candidateBindings = bindingSnapshot(traceId);
                TraceRecord after = findRecord(traceId).orElseThrow(() -> new AgentTraceStoreException(NOT_FOUND));
                BindingSnapshot afterBindings = bindingSnapshot(traceId);
                last = after;
                lastBindings = afterBindings;
                encoded = candidate == null ? List.of() : List.copyOf(candidate);
                if (sameSnapshot(before, after) && candidateBindings.equals(afterBindings)) {
                    stable = true;
                    break;
                }
            }
            if (last == null) throw new AgentTraceStoreException(NOT_FOUND);
            return verify(traceId, last, encoded, lastBindings, stable);
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    private TraceReadResult verify(
            UUID traceId,
            TraceRecord trace,
            List<String> encoded,
            BindingSnapshot bindings,
            boolean stable) {
        List<StoredEvent> events = new ArrayList<>(encoded.size());
        Set<String> problems = new LinkedHashSet<>();
        if (!stable) problems.add("READ_SNAPSHOT_CHANGED");
        problems.addAll(bindings.problemCodes());
        long observedBytes = encoded.stream()
                .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length)
                .sum();
        if (trace.session().eventCount() != encoded.size()) problems.add("EVENT_COUNT_MISMATCH");
        if (trace.session().storedBytes() != observedBytes) problems.add("STORED_BYTES_MISMATCH");

        String expectedPredecessor = CHAIN_GENESIS;
        for (int index = 0; index < encoded.size(); index++) {
            long expectedSequence = index + 1L;
            try {
                EncryptedEvent envelope = json.readValue(encoded.get(index), EncryptedEvent.class);
                if (envelope.sequence() != expectedSequence) {
                    problems.add("SEQUENCE_MISMATCH_AT_" + expectedSequence);
                }
                if (!expectedPredecessor.equals(envelope.predecessor())) {
                    problems.add("CHAIN_PREDECESSOR_MISMATCH_AT_" + expectedSequence);
                }
                byte[] nonce = Base64.getDecoder().decode(envelope.nonce());
                byte[] ciphertext = Base64.getDecoder().decode(envelope.ciphertext());
                String calculated = chain(
                        traceId,
                        envelope.sequence(),
                        envelope.predecessor(),
                        envelope.eventId(),
                        envelope.kind(),
                        envelope.keyVersion(),
                        nonce,
                        ciphertext);
                if (!calculated.equals(envelope.chainCommitment())) {
                    problems.add("CHAIN_COMMITMENT_MISMATCH_AT_" + expectedSequence);
                }
                AgentTraceEvent event = decrypt(traceId, envelope, nonce, ciphertext);
                events.add(new StoredEvent(envelope.sequence(), event));
                expectedPredecessor = envelope.chainCommitment();
            } catch (IOException | RuntimeException exception) {
                problems.add("EVENT_ENVELOPE_INVALID_AT_" + expectedSequence);
            }
        }
        verifyBindingEvents(traceId, events, bindings, problems);
        if (!trace.chainHead().equals(expectedPredecessor)) problems.add("CHAIN_HEAD_MISMATCH");
        if (trace.session().state() == TraceState.SEALED) {
            if (trace.sealProof().isEmpty()) {
                problems.add("SEAL_PROOF_MISSING");
            } else if (!validSealProof(traceId, trace, bindings.digest())) {
                problems.add("SEAL_PROOF_INVALID");
            }
        } else if (trace.sealProof().isPresent()) {
            problems.add("SEAL_PROOF_ON_ACTIVE_TRACE");
        }
        return new TraceReadResult(
                trace.session(), events, List.copyOf(problems), encoded.size(), observedBytes);
    }

    private boolean validSealProof(UUID traceId, TraceRecord trace, String bindingDigest) {
        try {
            SealProof proof = trace.sealProof().orElseThrow();
            if (!MessageDigest.isEqual(
                    proof.bindingDigest().getBytes(StandardCharsets.UTF_8),
                    bindingDigest.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
            byte[] plaintext = cipher.decrypt(
                    sealAssociatedData(traceId, trace.session(), trace.chainHead(), bindingDigest),
                    new EncryptedPayload(
                            Base64.getDecoder().decode(proof.ciphertext()),
                            Base64.getDecoder().decode(proof.nonce()),
                            Short.parseShort(proof.keyVersion())));
            return MessageDigest.isEqual(SEAL_PLAINTEXT, plaintext);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private AgentTraceEvent decrypt(UUID traceId, EncryptedEvent envelope, byte[] nonce, byte[] ciphertext) {
        byte[] plaintext = cipher.decrypt(
                associatedData(
                        traceId,
                        envelope.sequence(),
                        envelope.predecessor(),
                        envelope.eventId(),
                        envelope.kind(),
                        envelope.schemaVersion()),
                new EncryptedPayload(ciphertext, nonce, envelope.keyVersion()));
        try {
            AgentTraceEvent event = json.readValue(plaintext, AgentTraceEvent.class);
            if (!envelope.eventId().equals(event.context().eventId()) || envelope.kind() != event.kind()) {
                throw new AgentTraceStoreException(CORRUPT);
            }
            return event;
        } catch (IOException exception) {
            throw new AgentTraceStoreException(CORRUPT, exception);
        }
    }

    private EncryptedRecord record(
            UUID traceId, long sequence, String predecessor, AgentTraceEvent event, byte[] plaintext) {
        try {
            EncryptedPayload encrypted = cipher.encrypt(
                    associatedData(
                            traceId,
                            sequence,
                            predecessor,
                            event.context().eventId(),
                            event.kind(),
                            EVENT_SCHEMA_VERSION),
                    plaintext);
            String chainCommitment = chain(
                    traceId,
                    sequence,
                    predecessor,
                    event.context().eventId(),
                    event.kind(),
                    encrypted.keyVersion(),
                    encrypted.nonce(),
                    encrypted.ciphertext());
            EncryptedEvent envelope = new EncryptedEvent(
                    EVENT_SCHEMA_VERSION,
                    sequence,
                    predecessor,
                    chainCommitment,
                    event.context().eventId(),
                    event.kind(),
                    encrypted.keyVersion(),
                    Base64.getEncoder().encodeToString(encrypted.nonce()),
                    Base64.getEncoder().encodeToString(encrypted.ciphertext()));
            String encoded = json.writeValueAsString(envelope);
            return new EncryptedRecord(
                    encoded, encoded.getBytes(StandardCharsets.UTF_8).length, chainCommitment);
        } catch (JsonProcessingException exception) {
            throw new AgentTraceStoreException(CORRUPT, exception);
        }
    }

    private byte[] associatedData(
            UUID traceId,
            long sequence,
            String predecessor,
            UUID eventId,
            AgentTraceEvent.EventKind kind,
            int schemaVersion) {
        if (traceId == null || sequence < 1 || !isDigest(predecessor) || eventId == null || kind == null
                || schemaVersion != EVENT_SCHEMA_VERSION) {
            throw new AgentTraceStoreException(CORRUPT);
        }
        return ("rulepilot-private-agent-trace-event-v" + schemaVersion + "\u0000" + traceId + "\u0000"
                        + sequence + "\u0000" + predecessor + "\u0000" + eventId + "\u0000" + kind.name())
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sealAssociatedData(
            UUID traceId, TraceSession session, String chainHead, String bindingDigest) {
        if (traceId == null || session == null || !traceId.equals(session.traceId()) || !isDigest(chainHead)
                || !isDigest(bindingDigest) || session.state() != TraceState.SEALED || session.sealedAt() == null) {
            throw new AgentTraceStoreException(CORRUPT);
        }
        MessageDigest digest = sha256();
        update(digest, "rulepilot-private-agent-trace-seal-aad-v2".getBytes(StandardCharsets.UTF_8));
        update(digest, traceId.toString().getBytes(StandardCharsets.UTF_8));
        update(digest, session.ownerIdentity().getBytes(StandardCharsets.UTF_8));
        update(digest, session.sessionDigest().getBytes(StandardCharsets.UTF_8));
        update(digest, session.state().name().getBytes(StandardCharsets.UTF_8));
        update(digest, session.integrity().name().getBytes(StandardCharsets.UTF_8));
        update(digest, session.incompleteReason().getBytes(StandardCharsets.UTF_8));
        update(digest, epoch(session.createdAt()).getBytes(StandardCharsets.UTF_8));
        update(digest, epoch(session.captureUntil()).getBytes(StandardCharsets.UTF_8));
        update(digest, epoch(session.expiresAt()).getBytes(StandardCharsets.UTF_8));
        update(digest, epoch(session.sealedAt()).getBytes(StandardCharsets.UTF_8));
        update(digest, Long.toString(session.eventCount()).getBytes(StandardCharsets.UTF_8));
        update(digest, Long.toString(session.storedBytes()).getBytes(StandardCharsets.UTF_8));
        update(digest, chainHead.getBytes(StandardCharsets.UTF_8));
        update(digest, bindingDigest.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private BindingSnapshot bindingSnapshot(UUID traceId) {
        Set<String> rawMembers = redis.opsForSet().members(bindingsKey(traceId));
        List<String> members = rawMembers == null
                ? List.of()
                : rawMembers.stream().sorted().toList();
        List<BindingRecord> records = new ArrayList<>(members.size());
        Set<String> problems = new LinkedHashSet<>();
        for (String member : members) {
            if (member == null || !member.startsWith(resourcePrefix())) {
                problems.add("RESOURCE_BINDING_SET_INVALID");
                continue;
            }
            String value = redis.opsForValue().get(member);
            String reference = value == null ? "" : value;
            records.add(new BindingRecord(member, reference));
            if (reference.isBlank()) {
                problems.add("RESOURCE_BINDING_MISSING");
            } else if (!traceId.toString().equals(reference)) {
                problems.add("RESOURCE_BINDING_CONFLICT");
            }
        }
        MessageDigest digest = sha256();
        update(digest, "rulepilot-private-agent-trace-bindings-v1".getBytes(StandardCharsets.UTF_8));
        update(digest, traceId.toString().getBytes(StandardCharsets.UTF_8));
        update(digest, Integer.toString(records.size()).getBytes(StandardCharsets.UTF_8));
        for (BindingRecord record : records) {
            update(digest, record.resourceKey().getBytes(StandardCharsets.UTF_8));
            update(digest, record.traceReference().getBytes(StandardCharsets.UTF_8));
        }
        return new BindingSnapshot(records, HexFormat.of().formatHex(digest.digest()), List.copyOf(problems));
    }

    private void verifyBindingEvents(
            UUID traceId,
            List<StoredEvent> events,
            BindingSnapshot bindings,
            Set<String> problems) {
        Map<String, String> observed = new java.util.LinkedHashMap<>();
        bindings.records().forEach(record -> observed.put(record.resourceKey(), record.traceReference()));
        for (StoredEvent stored : events) {
            if (!(stored.event() instanceof BindingOrFailure binding)
                    || binding.signal() != LifecycleSignal.BINDING
                    || binding.childResource() == null) {
                continue;
            }
            String reference = observed.get(resourceKey(binding.childResource()));
            if (reference == null || reference.isBlank()) {
                problems.add("RESOURCE_BINDING_MISSING");
            } else if (!traceId.toString().equals(reference)) {
                problems.add("RESOURCE_BINDING_CONFLICT");
            }
        }
    }

    private boolean bindingProblem(String code) {
        return code != null && code.startsWith("RESOURCE_BINDING_");
    }

    private String chain(
            UUID traceId,
            long sequence,
            String predecessor,
            UUID eventId,
            AgentTraceEvent.EventKind kind,
            short keyVersion,
            byte[] nonce,
            byte[] ciphertext) {
        if (traceId == null || sequence < 1 || !isDigest(predecessor) || eventId == null || kind == null
                || keyVersion < 1 || nonce == null || nonce.length == 0 || ciphertext == null || ciphertext.length == 0) {
            throw new AgentTraceStoreException(CORRUPT);
        }
        MessageDigest digest = sha256();
        update(digest, "rulepilot-private-agent-trace-chain-v2".getBytes(StandardCharsets.UTF_8));
        update(digest, traceId.toString().getBytes(StandardCharsets.UTF_8));
        update(digest, Long.toString(sequence).getBytes(StandardCharsets.UTF_8));
        update(digest, predecessor.getBytes(StandardCharsets.UTF_8));
        update(digest, eventId.toString().getBytes(StandardCharsets.UTF_8));
        update(digest, kind.name().getBytes(StandardCharsets.UTF_8));
        update(digest, Short.toString(keyVersion).getBytes(StandardCharsets.UTF_8));
        update(digest, nonce);
        update(digest, ciphertext);
        return HexFormat.of().formatHex(digest.digest());
    }

    private Optional<TraceRecord> findRecord(UUID traceId) {
        if (traceId == null) return Optional.empty();
        try {
            Map<Object, Object> fields = redis.opsForHash().entries(metaKey(traceId));
            if (fields == null || fields.isEmpty()) return Optional.empty();
            String chainHead = field(fields, "chainHead");
            if (!isDigest(chainHead)) throw new AgentTraceStoreException(CORRUPT);
            return Optional.of(new TraceRecord(session(traceId, fields), chainHead, sealProof(fields)));
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    private TraceSession session(UUID traceId, Map<Object, Object> fields) {
        try {
            return new TraceSession(
                    traceId,
                    requiredDigest(fields, "ownerIdentity"),
                    field(fields, "sessionDigest"),
                    TraceState.valueOf(field(fields, "state")),
                    TraceIntegrity.valueOf(field(fields, "integrity")),
                    field(fields, "incompleteReason"),
                    instant(fields, "createdAt"),
                    instant(fields, "captureUntil"),
                    instant(fields, "expiresAt"),
                    optionalInstant(fields, "sealedAt"),
                    Long.parseLong(field(fields, "eventCount")),
                    Long.parseLong(field(fields, "storedBytes")));
        } catch (IllegalArgumentException exception) {
            throw new AgentTraceStoreException(CORRUPT, exception);
        }
    }

    private void markTruncated(UUID traceId, String reasonCode, Instant sealedAt) {
        try {
            Long result = redis.execute(
                    MARK_TRUNCATED, List.of(metaKey(traceId)), reason(reasonCode), epoch(sealedAt));
            if (result == null) throw new AgentTraceStoreException(UNAVAILABLE);
            if (result < -1 || result > 1) throw new AgentTraceStoreException(CORRUPT);
        } catch (AgentTraceStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTraceStoreException(UNAVAILABLE, exception);
        }
    }

    private void bestEffortProveRejectedAppend(UUID traceId, Instant rejectedAt) {
        try {
            seal(traceId, rejectedAt);
        } catch (RuntimeException ignored) {
            // The atomic append rejection already made the trace incomplete; a missing proof also exports incomplete.
        }
    }

    private boolean sameSnapshot(TraceRecord first, TraceRecord second) {
        return first.session().equals(second.session())
                && first.chainHead().equals(second.chainHead())
                && first.sealProof().equals(second.sealProof());
    }

    private Optional<SealProof> sealProof(Map<Object, Object> fields) {
        String bindingDigest = field(fields, "sealBindingDigest");
        String keyVersion = field(fields, "sealKeyVersion");
        String nonce = field(fields, "sealNonce");
        String ciphertext = field(fields, "sealCiphertext");
        if (bindingDigest.isBlank() && keyVersion.isBlank() && nonce.isBlank() && ciphertext.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SealProof(bindingDigest, keyVersion, nonce, ciphertext));
    }

    private void requireIdentity(
            UUID traceId,
            String ownerUsername,
            String sessionDigest,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt) {
        checkedOwner(ownerUsername);
        if (traceId == null || sessionDigest == null || sessionDigest.isBlank() || sessionDigest.length() > 128
                || createdAt == null || captureUntil == null || expiresAt == null
                || !createdAt.isBefore(captureUntil) || !captureUntil.isBefore(expiresAt)) {
            throw new IllegalArgumentException("private agent trace identity is invalid");
        }
    }

    private String checkedOwner(String value) {
        String checked = value == null ? "" : value.strip();
        if (checked.isBlank() || checked.length() > 120) {
            throw new IllegalArgumentException("private agent trace owner is invalid");
        }
        return checked;
    }

    private String reason(String value) {
        String checked = value == null ? "" : value.strip();
        if (!checked.matches("[A-Z0-9_]{1,120}")) {
            throw new IllegalArgumentException("private agent trace reason code is invalid");
        }
        return checked;
    }

    private String field(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? "" : value.toString();
    }

    private Instant instant(Map<Object, Object> fields, String name) {
        return Instant.ofEpochMilli(Long.parseLong(field(fields, name)));
    }

    private Instant optionalInstant(Map<Object, Object> fields, String name) {
        String value = field(fields, name);
        return value.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private String epoch(Instant value) {
        return Long.toString(value.toEpochMilli());
    }

    private String metaKey(UUID traceId) {
        return traceKey(traceId) + ":meta";
    }

    private String eventsKey(UUID traceId) {
        return traceKey(traceId) + ":events";
    }

    private String bindingsKey(UUID traceId) {
        return traceKey(traceId) + ":bindings";
    }

    private String traceKey(UUID traceId) {
        return prefix + "{" + traceId + "}";
    }

    private String ownerSlotKey(String ownerIdentity) {
        if (!isDigest(ownerIdentity)) throw new IllegalArgumentException("private agent trace owner identity is invalid");
        return prefix + "owner:" + ownerIdentity;
    }

    private String resourcePrefix() {
        return prefix + "resource:";
    }

    private String resourceKey(ResourceRef resource) {
        return resourcePrefix() + resource.type().name().toLowerCase(Locale.ROOT) + ":" + resource.id();
    }

    private void bestEffortCleanup(UUID traceId, String ownerUsername) {
        try {
            redis.delete(List.of(metaKey(traceId), eventsKey(traceId), bindingsKey(traceId)));
            redis.execute(
                    DELETE_IF_VALUE,
                    List.of(ownerSlotKey(ownerIdentity(ownerUsername))),
                    traceId.toString());
        } catch (RuntimeException ignored) {
            // Fixed Redis expiry remains the recovery boundary after an ambiguous create failure.
        }
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String requiredDigest(Map<Object, Object> fields, String name) {
        String value = field(fields, name);
        if (!isDigest(value)) throw new AgentTraceStoreException(CORRUPT);
        return value;
    }

    private String ownerIdentity(String ownerUsername) {
        byte[] digest = cipher.stableKeyedDigest(
                OWNER_DIGEST_DOMAIN, checkedOwner(ownerUsername).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(new byte[] {
            (byte) (value.length >>> 24),
            (byte) (value.length >>> 16),
            (byte) (value.length >>> 8),
            (byte) value.length
        });
        digest.update(value);
    }

    private record TraceRecord(TraceSession session, String chainHead, Optional<SealProof> sealProof) {}

    private record BindingRecord(String resourceKey, String traceReference) {
        private BindingRecord {
            if (resourceKey == null || resourceKey.isBlank() || traceReference == null) {
                throw new IllegalArgumentException("private agent trace binding record is invalid");
            }
        }
    }

    private record BindingSnapshot(List<BindingRecord> records, String digest, List<String> problemCodes) {
        private BindingSnapshot {
            records = records == null ? List.of() : List.copyOf(records);
            problemCodes = problemCodes == null ? List.of() : List.copyOf(problemCodes);
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("private agent trace binding digest is invalid");
            }
        }

        private static BindingSnapshot empty() {
            MessageDigest digest = sha256();
            update(digest, "rulepilot-private-agent-trace-bindings-empty-v1".getBytes(StandardCharsets.UTF_8));
            return new BindingSnapshot(List.of(), HexFormat.of().formatHex(digest.digest()), List.of());
        }
    }

    private record SealProof(String bindingDigest, String keyVersion, String nonce, String ciphertext) {}

    private record EncryptedRecord(String encoded, long eventBytes, String chainCommitment) {}

    private record EncryptedEvent(
            int schemaVersion,
            long sequence,
            String predecessor,
            String chainCommitment,
            UUID eventId,
            AgentTraceEvent.EventKind kind,
            short keyVersion,
            String nonce,
            String ciphertext) {
        private EncryptedEvent {
            if (schemaVersion != EVENT_SCHEMA_VERSION || sequence < 1 || !digest(predecessor)
                    || !digest(chainCommitment) || eventId == null || kind == null || keyVersion < 1
                    || nonce == null || nonce.isBlank() || ciphertext == null || ciphertext.isBlank()) {
                throw new IllegalArgumentException("encrypted agent trace event is invalid");
            }
        }

        private static boolean digest(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }
    }
}
