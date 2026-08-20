package com.rulepilot.modelconfig;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ModelConfigurationStore {

    Optional<StoredConfiguration> personal(String username);

    Optional<StoredConfiguration> platform();

    long savePersonalProvider(String username, StoredProvider provider, Instant updatedAt);

    long removePersonalProvider(String username, String provider, Instant updatedAt);

    long savePersonalAssignments(String username, StoredAssignments assignments, Instant updatedAt);

    long savePlatformProvider(String administrator, StoredProvider provider, Instant updatedAt);

    long removePlatformProvider(String administrator, String provider, Instant updatedAt);

    long savePlatformAssignments(String administrator, StoredAssignments assignments, Instant updatedAt);

    record StoredConfiguration(List<StoredProvider> providers, StoredAssignments assignments, long revision) {
        public StoredConfiguration {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }

    record StoredProvider(
            String provider,
            ModelCredentialCipher.EncryptedSecret encryptedApiKey,
            String baseUrl,
            String model,
            boolean visionCapable,
            long revision) {}

    record StoredAssignments(
            String teaching,
            String visual,
            String answer,
            String critic,
            String recommendation,
            long revision) {}
}
