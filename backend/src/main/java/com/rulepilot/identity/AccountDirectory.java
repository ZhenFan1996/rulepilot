package com.rulepilot.identity;

import java.util.List;
import java.util.Set;

public interface AccountDirectory {

    List<Account> accounts();

    record Account(String username, String email, boolean enabled, Set<String> authorities) {
        public Account {
            authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
        }
    }
}
