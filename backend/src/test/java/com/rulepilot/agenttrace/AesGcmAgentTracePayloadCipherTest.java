package com.rulepilot.agenttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmAgentTracePayloadCipherTest {

    @Test
    void encryptsBytesWithAuthenticatedContextAndDefensiveCopies() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var cipher = new AesGcmAgentTracePayloadCipher(key, (short) 3, new SecureRandom());
        byte[] context = "trace:event:MODEL_TURN".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "private raw assistant output".getBytes(StandardCharsets.UTF_8);

        AgentTracePayloadCipher.EncryptedPayload encrypted = cipher.encrypt(context, plaintext);

        assertThat(encrypted.keyVersion()).isEqualTo((short) 3);
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(context, encrypted)).isEqualTo(plaintext);

        byte[] exposed = encrypted.ciphertext();
        exposed[0] ^= 1;
        assertThat(cipher.decrypt(context, encrypted)).isEqualTo(plaintext);
        assertThatThrownBy(() -> cipher.decrypt("other-event".getBytes(StandardCharsets.UTF_8), encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remainsUnavailableWithoutAKeyAndRejectsWrongKeySizes() {
        var unavailable = new AesGcmAgentTracePayloadCipher("", (short) 1);

        assertThat(unavailable.available()).isFalse();
        assertThatThrownBy(() -> unavailable.encrypt(new byte[] {1}, new byte[] {2}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmAgentTracePayloadCipher(
                        Base64.getEncoder().encodeToString(new byte[16]), (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesStableDomainSeparatedDigestsWithoutExposingTheInput() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var cipher = new AesGcmAgentTracePayloadCipher(key, (short) 1, new SecureRandom());
        byte[] owner = "alice".getBytes(StandardCharsets.UTF_8);

        byte[] first = cipher.stableKeyedDigest(
                "trace-owner-v1".getBytes(StandardCharsets.UTF_8), owner);
        byte[] repeated = cipher.stableKeyedDigest(
                "trace-owner-v1".getBytes(StandardCharsets.UTF_8), owner);
        byte[] otherDomain = cipher.stableKeyedDigest(
                "trace-session-v1".getBytes(StandardCharsets.UTF_8), owner);

        assertThat(first).hasSize(32).isEqualTo(repeated).isNotEqualTo(otherDomain).isNotEqualTo(owner);
        assertThatThrownBy(() -> cipher.stableKeyedDigest(new byte[0], owner))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
