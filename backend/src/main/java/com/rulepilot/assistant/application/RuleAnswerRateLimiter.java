package com.rulepilot.assistant.application;

import java.util.UUID;

public interface RuleAnswerRateLimiter {

    void checkUser(String username);

    Permit acquireModel(String username, UUID gameSessionId, String providerId);

    interface Permit extends AutoCloseable {

        @Override
        void close();
    }
}
