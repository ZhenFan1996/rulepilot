package com.rulepilot.modelconfig;

public interface ModelCredentialCipher {

    boolean available();

    EncryptedSecret encrypt(String context, String plaintext);

    String decrypt(String context, EncryptedSecret encrypted);

    record EncryptedSecret(byte[] ciphertext, byte[] nonce, short keyVersion) {
        public EncryptedSecret {
            ciphertext = ciphertext == null ? new byte[0] : ciphertext.clone();
            nonce = nonce == null ? new byte[0] : nonce.clone();
            if (ciphertext.length < 16 || ciphertext.length > 8_192 || nonce.length != 12 || keyVersion < 1) {
                throw new IllegalArgumentException("Encrypted model credential is invalid");
            }
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
