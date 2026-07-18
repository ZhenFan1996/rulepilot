package com.rulepilot.document.application;

import com.rulepilot.document.RuleDataVersion;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
class RuleDataVersionService implements RuleDataVersion {

    private final RuleDocumentRepository repository;

    RuleDataVersionService(RuleDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public long current(UUID documentVersionId) {
        return repository.ruleDataVersion(documentVersionId);
    }

    @Override
    @Transactional
    public long increment(UUID documentVersionId) {
        return repository.incrementRuleDataVersion(documentVersionId);
    }
}
