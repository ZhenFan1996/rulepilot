package com.rulepilot.identity.application;

public final class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email address is already registered");
    }
}
