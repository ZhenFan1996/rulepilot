package com.rulepilot.teaching.domain;

import java.util.List;

public enum TeachingSectionType {
    OBJECTIVE(true, List.of()),
    COMPONENTS(true, List.of(OBJECTIVE)),
    SETUP(true, List.of(COMPONENTS)),
    ROUND_STRUCTURE(true, List.of(SETUP)),
    PHASES(true, List.of(ROUND_STRUCTURE)),
    ACTIONS(true, List.of(PHASES)),
    END_CONDITIONS(true, List.of(ACTIONS)),
    SCORING(true, List.of(END_CONDITIONS)),
    TIE_BREAKERS(true, List.of(SCORING)),
    FIRST_ROUND_PRACTICE(false, List.of(ACTIONS)),
    COMMON_MISTAKES(false, List.of(ACTIONS)),
    RECAP(false, List.of(SCORING));

    private final boolean required;
    private final List<TeachingSectionType> dependencies;

    TeachingSectionType(boolean required, List<TeachingSectionType> dependencies) {
        this.required = required;
        this.dependencies = dependencies;
    }

    public boolean required() {
        return required;
    }

    public List<TeachingSectionType> dependencies() {
        return dependencies;
    }
}
