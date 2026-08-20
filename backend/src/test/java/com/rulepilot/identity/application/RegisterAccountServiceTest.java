package com.rulepilot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import java.util.HashSet;
import java.util.Set;

class RegisterAccountServiceTest {

    private final InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
    private final Set<String> emails = new HashSet<>();
    private final RegisterAccountService service =
            new RegisterAccountService(
                    users,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                    (username, email) -> emails.add(email));

    @Test
    void createsAUserAccountWithOnlyTheUserRole() {
        RegisterAccountService.RegistrationResult result = service.register(
                " New.Player ", " Player@Example.com ", "safe-password");

        assertThat(result.created()).isTrue();
        assertThat(result.username()).isEqualTo("new.player");
        assertThat(users.loadUserByUsername("new.player").getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        assertThat(users.loadUserByUsername("new.player").getPassword()).isNotEqualTo("safe-password");
    }

    @Test
    void reportsAnExistingUsernameWithoutReplacingItsPassword() {
        service.register("player", "first@example.com", "first-password");
        String originalHash = users.loadUserByUsername("player").getPassword();

        RegisterAccountService.RegistrationResult result = service.register(
                "PLAYER", "second@example.com", "second-password");

        assertThat(result.created()).isFalse();
        assertThat(users.loadUserByUsername("player").getPassword()).isEqualTo(originalHash);
    }

    @Test
    void rejectsWeakOrUnsupportedCredentials() {
        assertThatThrownBy(() -> service.register("ab", "player@example.com", "safe-password"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("valid-name", "player@example.com", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("valid-name", "not-an-email", "safe-password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheSameEmailRegardlessOfCase() {
        service.register("first-player", "Player@Example.com", "safe-password");

        assertThatThrownBy(() -> service.register(
                        "second-player", " player@example.COM ", "safe-password"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }
}
