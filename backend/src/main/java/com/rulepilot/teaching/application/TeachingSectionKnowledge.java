package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingSectionType;

public final class TeachingSectionKnowledge {

    private TeachingSectionKnowledge() {}

    public static Guidance forSection(TeachingSectionType type) {
        return switch (type) {
            case OBJECTIVE -> new Guidance(
                    "Explain what players are trying to achieve and how a winner is determined.",
                    "goal; player objective; relationship between objective and victory");
            case COMPONENTS -> new Guidance(
                    "Introduce only components players must recognize before setup.",
                    "component names; ownership or shared supply; quantities only when evidenced");
            case SETUP -> new Guidance(
                    "Turn setup rules into an executable table-ready sequence.",
                    "play area; shared supply; per-player items; starting player; initial resources or cards");
            case ROUND_STRUCTURE -> new Guidance(
                    "Give players a mental map of one round and turn order before details.",
                    "round start; player order; turn boundary; round end; repeat condition");
            case PHASES -> new Guidance(
                    "Explain phases in the exact order they occur and what moves play forward.",
                    "phase order; active player; mandatory steps; transition conditions");
            case ACTIONS -> new Guidance(
                    "Teach the legal choices on a turn, including timing, costs, and limits.",
                    "available actions; prerequisites; costs; limits; immediate results; prohibited choices");
            case END_CONDITIONS -> new Guidance(
                    "Make it clear exactly when normal play stops.",
                    "trigger; whether the current turn or round finishes; final actions; transition to scoring");
            case SCORING -> new Guidance(
                    "Provide a repeatable scoring procedure that players can execute at the table.",
                    "score categories; calculation order; bonuses; penalties; winner comparison");
            case TIE_BREAKERS -> new Guidance(
                    "Resolve tied final scores without extending beyond the written rule.",
                    "tie trigger; ordered tie breakers; shared victory only when evidenced");
            case FIRST_ROUND_PRACTICE -> new Guidance(
                    "Walk through a legal first round using only examples directly supported by evidence.",
                    "starting state; player choice; rule consequence; handoff to next player or phase");
            case COMMON_MISTAKES -> new Guidance(
                    "Highlight easy-to-miss restrictions and exceptions found in the evidence.",
                    "confusable rule; prohibited interpretation; exception; timing reminder");
            case RECAP -> new Guidance(
                    "Compress the complete play loop into a table-ready reminder.",
                    "objective; setup checkpoint; turn loop; end trigger; scoring checkpoint");
        };
    }

    public record Guidance(String objective, String coverageChecklist) {}
}
