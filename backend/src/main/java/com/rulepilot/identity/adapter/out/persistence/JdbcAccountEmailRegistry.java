package com.rulepilot.identity.adapter.out.persistence;

import com.rulepilot.identity.AccountEmailRegistry;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "rulepilot.persistence.jdbc-adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccountEmailRegistry implements AccountEmailRegistry {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAccountEmailRegistry(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String username, String normalizedEmail) {
        // Serialize claims for one normalized address so concurrent registrations cannot both pass the check.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))",
                Map.of("email", normalizedEmail),
                result -> { });
        return jdbc.update(
                        """
                        UPDATE app_user
                        SET email = :email
                        WHERE username = :username
                          AND email IS NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM app_user existing
                              WHERE lower(existing.email) = lower(:email)
                          )
                        """,
                        Map.of("username", username, "email", normalizedEmail))
                == 1;
    }
}
