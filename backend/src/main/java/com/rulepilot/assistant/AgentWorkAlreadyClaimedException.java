package com.rulepilot.assistant;

/** A duplicate worker delivery lost the one-shot admission race and must leave the winning run untouched. */
public final class AgentWorkAlreadyClaimedException extends RuntimeException {

    public AgentWorkAlreadyClaimedException() {
        super("queued agent work has already been claimed by another worker");
    }
}
