package com.rulepilot.agenttrace;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AesGcmAgentTracePayloadCipher implements AgentTracePayloadCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;

    private final byte[] key;
    private final short keyVersion;
    private final SecureRandom random;

    AesGcmAgentTracePayloadCipher(String encodedKey, short keyVersion) {
        this(decodedKey(encodedKey), keyVersion, new SecureRandom());
    }

    AesGcmAgentTracePayloadCipher(byte[] key, short keyVersion, SecureRandom random) {
        this.key = key == null ? new byte[0] : key.clone();
        if (this.key.length != 0 && this.key.length != KEY_BYTES) {
            throw new IllegalArgumentException("agent trace encryption key must contain 32 bytes");
        }
        if (keyVersion < 1 || random == null) {
            throw new IllegalArgumentException("agent trace cipher configuration is invalid");
        }
        this.keyVersion = keyVersion;
        this.random = random;
    }

    @Override
    public boolean available() {
        return key.length == KEY_BYTES;
    }

    @Override
    public EncryptedPayload encrypt(byte[] associatedData, byte[] plaintext) {
        requireAvailable();
        byte[] checkedAssociatedData = required(associatedData, "associated data");
        byte[] checkedPlaintext = required(plaintext, "plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(checkedAssociatedData);
            return new EncryptedPayload(cipher.doFinal(checkedPlaintext), nonce, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("agent trace payload could not be encrypted", exception);
        }
    }

    @Override
    public byte[] decrypt(byte[] associatedData, EncryptedPayload encrypted) {
        requireAvailable();
        byte[] checkedAssociatedData = required(associatedData, "associated data");
        if (encrypted == null || encrypted.keyVersion() != keyVersion) {
            throw new IllegalStateException("agent trace payload uses an unavailable key version");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(checkedAssociatedData);
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("agent trace payload could not be decrypted", exception);
        }
    }

    @Override
    public byte[] stableKeyedDigest(byte[] domain, byte[] value) {
        requireAvailable();
        byte[] checkedDomain = required(domain, "digest domain");
        byte[] checkedValue = required(value, "digest value");
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] purposeKey = derivation.doFinal(checkedDomain);
            Mac digest = Mac.getInstance("HmacSHA256");
            digest.init(new SecretKeySpec(purposeKey, "HmacSHA256"));
            return digest.doFinal(checkedValue);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("agent trace keyed digest could not be calculated", exception);
        }
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException("agent trace encryption is not configured");
    }

    private static byte[] required(byte[] value, String label) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("agent trace " + label + " is required");
        }
        return value.clone();
    }

    private static byte[] decodedKey(String value) {
        String checked = value == null ? "" : value.strip();
        if (checked.isEmpty()) return new byte[0];
        try {
            return Base64.getDecoder().decode(checked);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("agent trace encryption key must be base64", exception);
        }
    }
}
