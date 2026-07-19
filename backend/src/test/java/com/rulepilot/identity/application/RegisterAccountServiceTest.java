package com.rulepilot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

class RegisterAccountServiceTest {

    private final InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
    private final RegisterAccountService service =
            new RegisterAccountService(users, PasswordEncoderFactories.createDelegatingPasswordEncoder());

    @Test
    void createsAUserAccountWithOnlyTheUserRole() {
        RegisterAccountService.RegistrationResult result = service.register(" New.Player ", "safe-password");

        assertThat(result.created()).isTrue();
        assertThat(result.username()).isEqualTo("new.player");
        assertThat(users.loadUserByUsername("new.player").getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        assertThat(users.loadUserByUsername("new.player").getPassword()).isNotEqualTo("safe-password");
    }

    @Test
    void reportsAnExistingUsernameWithoutReplacingItsPassword() {
        service.register("player", "first-password");
        String originalHash = users.loadUserByUsername("player").getPassword();

        RegisterAccountService.RegistrationResult result = service.register("PLAYER", "second-password");

        assertThat(result.created()).isFalse();
        assertThat(users.loadUserByUsername("player").getPassword()).isEqualTo(originalHash);
    }

    @Test
    void rejectsWeakOrUnsupportedCredentials() {
        assertThatThrownBy(() -> service.register("ab", "safe-password"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("valid-name", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
