package com.rulepilot.ruling.application;

import com.rulepilot.ruling.domain.ConfirmedRuling;
import com.rulepilot.ruling.domain.RulingApplicability;
import java.util.Optional;
import java.util.UUID;

public interface ConfirmedRulingRepository {

    ConfirmedRuling save(ConfirmedRuling ruling);

    Optional<ConfirmedRuling> find(UUID rulingId);

    boolean existsConfirmed(RulingApplicability applicability, String normalizedQuestionHash);
}
