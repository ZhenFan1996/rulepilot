package com.rulepilot.document;

import java.util.UUID;

public interface RuleDataVersion {

    long current(UUID documentVersionId);

    long increment(UUID documentVersionId);
}
