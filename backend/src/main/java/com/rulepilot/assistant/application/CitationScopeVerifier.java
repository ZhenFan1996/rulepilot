package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.Verification;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CitationScopeVerifier implements EvidenceVerifier {

    static final String NO_EVIDENCE = "NO_EVIDENCE";
    static final String VERSION_MISMATCH = "VERSION_MISMATCH";
    static final String SOURCE_SNAPSHOT_CONFLICT = "SOURCE_SNAPSHOT_CONFLICT";
    static final String CLAIM_WITHOUT_CITATION = "CLAIM_WITHOUT_CITATION";
    static final String CITATION_OUTSIDE_EVIDENCE = "CITATION_OUTSIDE_EVIDENCE";

    @Override
    public Verification verify(VerificationRequest request) {
        if (request == null || request.documentVersionId() == null) {
            throw new IllegalArgumentException("evidence verification scope is required");
        }
        List<String> issues = new ArrayList<>();
        if (request.sources().isEmpty()) {
            issues.add(NO_EVIDENCE);
        }

        Map<UUID, EvidenceSource> uniqueSources = new HashMap<>();
        Set<UUID> conflictingSources = new HashSet<>();
        for (EvidenceSource source : request.sources()) {
            validateSource(source);
            if (!request.documentVersionId().equals(source.documentVersionId())) {
                addOnce(issues, VERSION_MISMATCH);
            }
            EvidenceSource previous = uniqueSources.putIfAbsent(source.chunkId(), source);
            if (previous != null && !previous.equals(source)) {
                conflictingSources.add(source.chunkId());
                addOnce(issues, SOURCE_SNAPSHOT_CONFLICT);
            }
        }

        Set<UUID> allowedIds = uniqueSources.keySet();
        for (EvidenceClaim claim : request.claims()) {
            if (claim == null || claim.text() == null || claim.text().isBlank() || claim.citationIds().isEmpty()) {
                addOnce(issues, CLAIM_WITHOUT_CITATION);
                continue;
            }
            if (claim.citationIds().stream().anyMatch(id -> id == null
                    || !allowedIds.contains(id)
                    || conflictingSources.contains(id))) {
                addOnce(issues, CITATION_OUTSIDE_EVIDENCE);
            }
        }

        VerificationStatus status = status(issues);
        return new Verification(status, issues);
    }

    private void validateSource(EvidenceSource source) {
        if (source == null || source.chunkId() == null || source.documentVersionId() == null
                || source.sectionType() == null || source.sectionType().isBlank()
                || source.excerpt() == null || source.excerpt().isBlank()
                || source.pageFrom() < 1 || source.pageTo() < source.pageFrom()) {
            throw new IllegalArgumentException("evidence source is invalid");
        }
    }

    private VerificationStatus status(List<String> issues) {
        if (issues.contains(VERSION_MISMATCH)) {
            return VerificationStatus.VERSION_CONFLICT;
        }
        if (issues.contains(SOURCE_SNAPSHOT_CONFLICT)) {
            return VerificationStatus.SOURCE_CONFLICT;
        }
        if (!issues.isEmpty()) {
            return VerificationStatus.INSUFFICIENT_EVIDENCE;
        }
        return VerificationStatus.VERIFIED;
    }

    private void addOnce(List<String> issues, String issue) {
        if (!issues.contains(issue)) {
            issues.add(issue);
        }
    }
}
