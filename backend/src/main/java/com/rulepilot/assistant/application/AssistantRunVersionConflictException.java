package com.rulepilot.assistant.application;

public class AssistantRunVersionConflictException extends RuntimeException {

    public AssistantRunVersionConflictException() {
        super("assistant run was advanced by another request");
    }
}
