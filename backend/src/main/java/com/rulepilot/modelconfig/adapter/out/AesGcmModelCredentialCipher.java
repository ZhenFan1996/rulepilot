package com.rulepilot.modelconfig.adapter.out;

import com.rulepilot.modelconfig.ModelCredentialCipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmModelCredentialCipher implements ModelCredentialCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;

    private final byte[] key;
    private final short keyVersion;
    private final SecureRandom random;

    @Autowired
    public AesGcmModelCredentialCipher(
            @Value("${rulepilot.models.credential-encryption-key:}") String encodedKey,
            @Value("${rulepilot.models.credential-encryption-key-version:1}") short keyVersion) {
        this(decodedKey(encodedKey), keyVersion, new SecureRandom());
    }

    AesGcmModelCredentialCipher(byte[] key, short keyVersion, SecureRandom random) {
        this.key = key == null ? new byte[0] : key.clone();
        if (this.key.length != 0 && this.key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Model credential encryption key must contain 32 bytes");
        }
        if (keyVersion < 1) throw new IllegalArgumentException("Model credential key version must be positive");
        this.keyVersion = keyVersion;
        this.random = random;
    }

    @Override
    public boolean available() {
        return key.length == KEY_BYTES;
    }

    @Override
    public EncryptedSecret encrypt(String context, String plaintext) {
        requireAvailable();
        String checkedContext = context(context);
        String checkedPlaintext = plaintext == null ? "" : plaintext.strip();
        if (checkedPlaintext.isBlank() || checkedPlaintext.length() > 4_096) {
            throw new IllegalArgumentException("Model credential is required and must be at most 4096 characters");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(checkedContext.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(checkedPlaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(encrypted, nonce, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Model credential could not be encrypted", exception);
        }
    }

    @Override
    public String decrypt(String context, EncryptedSecret encrypted) {
        requireAvailable();
        if (encrypted.keyVersion() != keyVersion) {
            throw new IllegalStateException("Model credential uses an unavailable encryption key version");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(context(context).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Model credential could not be decrypted", exception);
        }
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException("Durable model credentials are not configured");
    }

    private String context(String value) {
        String checked = value == null ? "" : value.strip();
        if (checked.isBlank() || checked.length() > 240) {
            throw new IllegalArgumentException("Model credential encryption context is invalid");
        }
        return checked;
    }

    private static byte[] decodedKey(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Model credential encryption key must be Base64", exception);
        }
    }
}
