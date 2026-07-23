package com.rulepilot.assistant;

/** The small player-visible language set; source rulebook language remains independent from this preference. */
public enum PlayerLocale {
    ZH_CN("Simplified Chinese"),
    EN("English");

    private final String promptName;

    PlayerLocale(String promptName) {
        this.promptName = promptName;
    }

    public String promptName() {
        return promptName;
    }

    public static PlayerLocale fromRequest(String value) {
        if (value == null || value.isBlank() || "zh-CN".equalsIgnoreCase(value.strip())) return ZH_CN;
        if ("en".equalsIgnoreCase(value.strip()) || "en-US".equalsIgnoreCase(value.strip())
                || "en-GB".equalsIgnoreCase(value.strip())) return EN;
        throw new IllegalArgumentException("player language is unsupported");
    }
}
