package com.rulepilot.identity;

/** Claims a normalized email address for an account inside the registration transaction. */
public interface AccountEmailRegistry {

    boolean claim(String username, String normalizedEmail);
}
