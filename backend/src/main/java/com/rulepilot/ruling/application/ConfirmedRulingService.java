package com.rulepilot.ruling.application;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.domain.ConfirmedRuling;
import com.rulepilot.ruling.domain.RulingApplicability;
import com.rulepilot.ruling.domain.RulingCitation;
import com.rulepilot.ruling.domain.RulingConfidence;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ConfirmedRulingService {

    private final CatalogEditionLookup catalog;
    private final DocumentVersionScopeLookup documents;
    private final RuleEvidenceLookup evidence;
    private final ConfirmedRulingRepository rulings;
    private final RuleDataVersion ruleDataVersion;
    private final Clock clock = Clock.systemUTC();

    public ConfirmedRulingService(
            CatalogEditionLookup catalog,
            DocumentVersionScopeLookup documents,
            RuleEvidenceLookup evidence,
            ConfirmedRulingRepository rulings,
            RuleDataVersion ruleDataVersion) {
        this.catalog = catalog;
        this.documents = documents;
        this.evidence = evidence;
        this.rulings = rulings;
        this.ruleDataVersion = ruleDataVersion;
    }

    @Transactional
    public ConfirmedRuling confirm(
            UUID documentVersionId,
            Set<UUID> expansionIds,
            String question,
            String shortVerdict,
            String explanation,
            List<UUID> citationChunkIds,
            List<String> exceptions,
            RulingConfidence confidence,
            String username) {
        var version = documents.findVersion(documentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("document version does not exist"));
        var edition = catalog.findEdition(version.editionId())
                .orElseThrow(() -> new IllegalArgumentException("game edition does not exist"));
        if (!"READY".equals(version.processingStatus())) {
            throw new IllegalArgumentException("document version is not ready for a confirmed ruling");
        }
        Set<UUID> selectedExpansions = expansionIds == null ? Set.of() : Set.copyOf(expansionIds);
        if (!edition.compatibleExpansionIds().containsAll(selectedExpansions)) {
            throw new IllegalArgumentException("an expansion is incompatible with the selected edition");
        }
        List<RulingCitation> citations = verifiedCitations(documentVersionId, citationChunkIds);
        ConfirmedRuling ruling = ConfirmedRuling.confirm(
                RulingApplicability.of(version.editionId(), documentVersionId, selectedExpansions),
                question, shortVerdict, explanation, citations, exceptions,
                confidence, username, Instant.now(clock));
        if (rulings.existsConfirmed(ruling.applicability(), ruling.normalizedQuestionHash())) {
            throw new IllegalArgumentException("a confirmed ruling already exists for this scope and question");
        }
        ConfirmedRuling saved = rulings.save(ruling);
        ruleDataVersion.increment(documentVersionId);
        return saved;
    }

    @Transactional(readOnly = true)
    public ConfirmedRuling get(UUID rulingId, String username) {
        ConfirmedRuling ruling = rulings.find(rulingId)
                .orElseThrow(() -> new IllegalArgumentException("confirmed ruling does not exist"));
        if (!ruling.createdBy().equals(username)) {
            throw new IllegalArgumentException("confirmed ruling does not exist");
        }
        return ruling;
    }

    @Transactional
    public ConfirmedRuling revise(
            UUID rulingId,
            long expectedVersion,
            String shortVerdict,
            String explanation,
            List<UUID> citationChunkIds,
            List<String> exceptions,
            RulingConfidence confidence,
            String username) {
        ConfirmedRuling current = get(rulingId, username);
        if (current.version() != expectedVersion) {
            throw new RulingVersionConflictException(current.version());
        }
        List<RulingCitation> citations = verifiedCitations(
                current.applicability().documentVersionId(), citationChunkIds);
        ConfirmedRuling revised = current.revise(
                shortVerdict, explanation, citations, exceptions, confidence, Instant.now(clock));
        ConfirmedRuling saved = rulings.update(revised, expectedVersion);
        ruleDataVersion.increment(current.applicability().documentVersionId());
        return saved;
    }

    private List<RulingCitation> verifiedCitations(UUID documentVersionId, List<UUID> citationChunkIds) {
        if (citationChunkIds == null || citationChunkIds.isEmpty()) {
            throw new IllegalArgumentException("at least one citation is required");
        }
        LinkedHashSet<UUID> requestedIds = new LinkedHashSet<>(citationChunkIds);
        if (requestedIds.size() != citationChunkIds.size() || requestedIds.contains(null)) {
            throw new IllegalArgumentException("citation chunk ids must be unique");
        }
        var trusted = evidence.findByChunkIds(documentVersionId, requestedIds).stream()
                .collect(Collectors.toUnmodifiableMap(RuleEvidenceHit::chunkId, Function.identity()));
        if (trusted.size() != requestedIds.size()) {
            throw new IllegalArgumentException("a citation is outside the selected document version");
        }
        return requestedIds.stream().map(trusted::get).map(hit -> new RulingCitation(
                hit.chunkId(), hit.documentVersionId(), hit.sectionType(), hit.heading(), hit.excerpt(),
                hit.pageFrom(), hit.pageTo())).toList();
    }
}
