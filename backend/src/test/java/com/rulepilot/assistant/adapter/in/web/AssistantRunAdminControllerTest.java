package com.rulepilot.assistant.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunDetails;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssistantRunAdminControllerTest {

    @Test
    void returnsTheSafeAdministrativeAuditFromTheApplicationPort() {
        AssistantRuns runs = mock(AssistantRuns.class);
        UUID runId = UUID.randomUUID();
        RunDetails details = mock(RunDetails.class);
        when(runs.findForAdministrativeAudit(runId)).thenReturn(Optional.of(details));

        assertThat(new AssistantRunAdminController(runs).audit(runId)).isSameAs(details);
    }

    @Test
    void returnsNotFoundInsteadOfLeakingAnUnknownRun() {
        AssistantRuns runs = mock(AssistantRuns.class);
        UUID runId = UUID.randomUUID();
        when(runs.findForAdministrativeAudit(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AssistantRunAdminController(runs).audit(runId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
