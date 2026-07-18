package com.rulepilot.assistant;

import java.util.List;
import java.util.UUID;

public interface EvidenceVerifier {

    Verification verify(VerificationRequest request);

    enum VerificationStatus {
        VERIFIED,
        INSUFFICIENT_EVIDENCE,
        VERSION_CONFLICT,
        SOURCE_CONFLICT
    }

    record VerificationRequest(
            UUID documentVersionId,
            List<EvidenceSource> sources,
            List<EvidenceClaim> claims) {
        public VerificationRequest {
            sources = sources == null ? List.of() : List.copyOf(sources);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    record EvidenceSource(
            UUID chunkId,
            UUID documentVersionId,
            String sectionType,
            String excerpt,
            int pageFrom,
            int pageTo) {}

    record EvidenceClaim(String text, List<UUID> citationIds) {
        public EvidenceClaim {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    record Verification(VerificationStatus status, List<String> issueCodes) {
        public Verification {
            issueCodes = List.copyOf(issueCodes);
        }

        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }
}
