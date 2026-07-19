package com.rulepilot.identity.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterAccountService {

    private static final Pattern USERNAME = Pattern.compile("[\\p{L}\\p{N}._-]{3,40}");

    private final UserDetailsManager users;
    private final PasswordEncoder passwordEncoder;

    RegisterAccountService(UserDetailsManager users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegistrationResult register(String requestedUsername, String password) {
        String username = normalizeUsername(requestedUsername);
        validatePassword(password);
        if (users.userExists(username)) {
            return new RegistrationResult(username, false);
        }
        try {
            users.createUser(User.withUsername(username)
                    .password(passwordEncoder.encode(password))
                    .roles("USER")
                    .build());
            return new RegistrationResult(username, true);
        } catch (DuplicateKeyException exception) {
            return new RegistrationResult(username, false);
        }
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Username must contain 3 to 40 supported characters");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        int byteLength = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (password == null || password.length() < 8 || byteLength > 72) {
            throw new IllegalArgumentException("Password length is invalid");
        }
    }

    public record RegistrationResult(String username, boolean created) {}
}
