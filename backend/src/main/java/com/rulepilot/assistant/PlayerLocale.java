package com.rulepilot.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The small player-visible language set; source rulebook language remains independent from this preference. */
public enum PlayerLocale {
    ZH_CN("Simplified Chinese"),
    EN("English");

    private final String promptName;

    private static final Set<String> ENGLISH_FUNCTION_WORDS = Set.of(
            "a", "an", "and", "are", "can", "do", "does", "for", "how", "i", "in", "is", "it", "of",
            "or", "should", "the", "this", "to", "we", "what", "when", "where", "which", "why", "with", "would",
            "you");

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

    /** Uses the current player turn for reply language; the requested UI language resolves only ambiguous fragments. */
    public static PlayerLocale forQuestion(String value, PlayerLocale fallback) {
        PlayerLocale safeFallback = fallback == null ? ZH_CN : fallback;
        if (value == null || value.isBlank()) return safeFallback;
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC);
        int hanCharacters = (int) normalized.codePoints().filter(PlayerLocale::isHanCodePoint).count();
        List<String> latinWords = latinWords(normalized);
        long englishSignals = latinWords.stream().filter(ENGLISH_FUNCTION_WORDS::contains).count();

        if (englishSignals >= 2) return EN;
        if (englishSignals >= 1 && latinWords.size() >= 2 && hanCharacters <= 1) return EN;
        if (hanCharacters >= 4 && (latinWords.size() <= 3 || hanCharacters >= latinWords.size())) return ZH_CN;
        if (latinWords.size() >= 4 && hanCharacters <= 4) return EN;
        if (hanCharacters >= 2 && latinWords.isEmpty()) return ZH_CN;
        return safeFallback;
    }

    private static List<String> latinWords(String value) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (isAsciiLatinLetter(codePoint)) {
                current.appendCodePoint(Character.toLowerCase(codePoint));
            } else if (!current.isEmpty()) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) words.add(current.toString().toLowerCase(Locale.ROOT));
        return List.copyOf(words);
    }

    private static boolean isAsciiLatinLetter(int codePoint) {
        return codePoint >= 'A' && codePoint <= 'Z' || codePoint >= 'a' && codePoint <= 'z';
    }

    private static boolean isHanCodePoint(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x4dbf
                || codePoint >= 0x4e00 && codePoint <= 0x9fff
                || codePoint >= 0x20000 && codePoint <= 0x2fa1f;
    }
}
