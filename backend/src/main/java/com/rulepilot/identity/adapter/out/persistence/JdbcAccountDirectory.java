package com.rulepilot.identity.adapter.out.persistence;

import com.rulepilot.identity.AccountDirectory;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class JdbcAccountDirectory implements AccountDirectory {

    private final JdbcTemplate jdbc;

    public JdbcAccountDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Account> accounts() {
        var accounts = new LinkedHashMap<String, MutableAccount>();
        jdbc.query(
                """
                SELECT u.username, u.email, u.enabled, a.authority
                FROM app_user u
                LEFT JOIN app_user_authority a ON a.username = u.username
                ORDER BY u.username, a.authority
                """,
                result -> {
                    String username = result.getString("username");
                    String email = result.getString("email");
                    boolean enabled = result.getBoolean("enabled");
                    MutableAccount account = accounts.computeIfAbsent(
                            username,
                            ignored -> new MutableAccount(username, email, enabled));
                    String authority = result.getString("authority");
                    if (authority != null && !authority.isBlank()) account.authorities().add(authority);
                });
        return accounts.values().stream()
                .map(account -> new Account(account.username(), account.email(), account.enabled(), account.authorities()))
                .toList();
    }

    private record MutableAccount(String username, String email, boolean enabled, LinkedHashSet<String> authorities) {
        private MutableAccount(String username, String email, boolean enabled) {
            this(username, email, enabled, new LinkedHashSet<>());
        }
    }
}
