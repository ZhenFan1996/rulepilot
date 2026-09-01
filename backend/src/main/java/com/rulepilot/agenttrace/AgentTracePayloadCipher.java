package com.rulepilot.agenttrace;

interface AgentTracePayloadCipher {

    boolean available();

    EncryptedPayload encrypt(byte[] associatedData, byte[] plaintext);

    byte[] decrypt(byte[] associatedData, EncryptedPayload encrypted);

    byte[] stableKeyedDigest(byte[] domain, byte[] value);

    record EncryptedPayload(byte[] ciphertext, byte[] nonce, short keyVersion) {
        public EncryptedPayload {
            ciphertext = ciphertext == null ? new byte[0] : ciphertext.clone();
            nonce = nonce == null ? new byte[0] : nonce.clone();
            if (ciphertext.length < 16 || nonce.length != 12 || keyVersion < 1) {
                throw new IllegalArgumentException("encrypted agent trace payload is invalid");
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
