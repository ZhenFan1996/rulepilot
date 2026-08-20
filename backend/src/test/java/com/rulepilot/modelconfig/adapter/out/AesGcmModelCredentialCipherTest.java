package com.rulepilot.modelconfig.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class AesGcmModelCredentialCipherTest {

    @Test
    void roundTripsASecretOnlyInsideTheExactAccountProviderContext() {
        var cipher = new AesGcmModelCredentialCipher(new byte[32], (short) 1, deterministicRandom());

        var encrypted = cipher.encrypt("PERSONAL|alice|deepseek", "private-api-key");

        assertThat(containsSequence(encrypted.ciphertext(), "private-api-key".getBytes())).isFalse();
        assertThat(cipher.decrypt("PERSONAL|alice|deepseek", encrypted)).isEqualTo("private-api-key");
        assertThatThrownBy(() -> cipher.decrypt("PERSONAL|bob|deepseek", encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model credential could not be decrypted");
    }

    @Test
    void refusesDurableWritesWhenNoMasterKeyIsConfigured() {
        var cipher = new AesGcmModelCredentialCipher(new byte[0], (short) 1, deterministicRandom());

        assertThat(cipher.available()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("PLATFORM|qwen", "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable model credentials are not configured");
    }

    private SecureRandom deterministicRandom() {
        return new SecureRandom(new byte[] {1, 2, 3, 4});
    }

    private boolean containsSequence(byte[] value, byte[] sequence) {
        for (int start = 0; start <= value.length - sequence.length; start++) {
            int offset = 0;
            while (offset < sequence.length && value[start + offset] == sequence[offset]) offset++;
            if (offset == sequence.length) return true;
        }
        return false;
    }
}
