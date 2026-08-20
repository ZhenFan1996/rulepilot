package com.rulepilot.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.identity.application.EmailAlreadyRegisteredException;
import com.rulepilot.identity.application.RegisterAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthenticationControllerTest {

    private final RegisterAccountService registrations = mock(RegisterAccountService.class);
    private final AuthenticationController controller = new AuthenticationController(registrations);

    @Test
    void returnsAFieldSpecificConflictForAnExistingEmail() {
        when(registrations.register("new-player", "used@example.com", "safe-password"))
                .thenThrow(new EmailAlreadyRegisteredException());

        var response = controller.register(new AuthenticationController.RegistrationRequest(
                "new-player", "used@example.com", "safe-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void returnsBadRequestForInvalidRegistrationFields() {
        when(registrations.register("new-player", "not-an-email", "safe-password"))
                .thenThrow(new IllegalArgumentException("Email address is invalid"));

        var response = controller.register(new AuthenticationController.RegistrationRequest(
                "new-player", "not-an-email", "safe-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REGISTRATION");
    }
}
