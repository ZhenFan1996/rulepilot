package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRuns.StepSnapshot;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.domain.AssistantRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantRunRepository {

    void insert(AssistantRun run, String summary);

    boolean update(AssistantRun previous, AssistantRun changed, String summary);

    Optional<AssistantRun> find(UUID runId);

    Optional<AssistantRun> findLatest(AssistantRunMode mode, UUID subjectId, String ownerUsername);

    List<AssistantRun> findNonTerminal(AssistantRunMode mode);

    List<StepSnapshot> steps(UUID runId);
}
