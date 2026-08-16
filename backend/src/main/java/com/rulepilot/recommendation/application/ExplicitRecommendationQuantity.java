package com.rulepilot.recommendation.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/** Reads only an unambiguous, explicitly counted recommendation result set. */
final class ExplicitRecommendationQuantity {

    private static final Set<String> ENGLISH_RESULT_NOUNS = Set.of(
            "game", "games", "option", "options", "pick", "picks", "recommendation", "recommendations");

    private ExplicitRecommendationQuantity() {}

    static OptionalInt from(String message, int maximum) {
        if (message == null || message.isBlank() || maximum < 1) return OptionalInt.empty();
        LinkedHashSet<Integer> counts = new LinkedHashSet<>();
        collectChineseMeasureCounts(message, maximum, counts);
        collectEnglishCounts(message, maximum, counts);
        return counts.size() == 1 ? OptionalInt.of(counts.getFirst()) : OptionalInt.empty();
    }

    private static void collectChineseMeasureCounts(
            String message,
            int maximum,
            Set<Integer> counts) {
        for (int index = 0; index < message.length(); index++) {
            char measure = message.charAt(index);
            if (measure != '款' && (measure != '个' || !followedByResultNoun(message, index + 1))) continue;
            int cursor = previousNonWhitespace(message, index - 1);
            if (cursor < 0) continue;
            int chinese = chineseDigit(message.charAt(cursor));
            if (chinese > 0) {
                if (!negated(message, cursor)) addIfSupported(chinese, maximum, counts);
                continue;
            }
            int end = cursor + 1;
            while (cursor >= 0 && Character.digit(message.charAt(cursor), 10) >= 0) cursor--;
            if (end == cursor + 1) continue;
            int start = cursor + 1;
            if (negated(message, start) || precededByNumericRange(message, start)) continue;
            int value = 0;
            for (int digitIndex = start; digitIndex < end; digitIndex++) {
                value = value * 10 + Character.digit(message.charAt(digitIndex), 10);
            }
            addIfSupported(value, maximum, counts);
        }
    }

    private static boolean followedByResultNoun(String message, int start) {
        int cursor = start;
        while (cursor < message.length() && Character.isWhitespace(message.charAt(cursor))) cursor++;
        String suffix = message.substring(cursor);
        return suffix.startsWith("不同方向")
                || suffix.startsWith("方向")
                || suffix.startsWith("候选")
                || suffix.startsWith("选择")
                || suffix.startsWith("推荐")
                || suffix.startsWith("桌游")
                || suffix.startsWith("游戏");
    }

    private static boolean negated(String message, int numberStart) {
        int start = Math.max(0, numberStart - 6);
        String prefix = message.substring(start, numberStart);
        return prefix.indexOf('不') >= 0 || prefix.indexOf('别') >= 0;
    }

    private static boolean precededByNumericRange(String message, int numberStart) {
        int cursor = previousNonWhitespace(message, numberStart - 1);
        if (cursor < 0 || "-–—到至".indexOf(message.charAt(cursor)) < 0) return false;
        cursor = previousNonWhitespace(message, cursor - 1);
        return cursor >= 0 && (Character.digit(message.charAt(cursor), 10) >= 0
                || chineseDigit(message.charAt(cursor)) > 0);
    }

    private static void collectEnglishCounts(
            String message,
            int maximum,
            Set<Integer> counts) {
        List<String> tokens = asciiWordsAndNumbers(message);
        for (int index = 1; index < tokens.size(); index++) {
            if (!ENGLISH_RESULT_NOUNS.contains(tokens.get(index))) continue;
            int countIndex = index - 1;
            if ("board".equals(tokens.get(countIndex)) && countIndex > 0) countIndex--;
            addIfSupported(englishCount(tokens.get(countIndex)), maximum, counts);
        }
    }

    private static List<String> asciiWordsAndNumbers(String message) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < message.length(); index++) {
            char character = message.charAt(index);
            if (character < 128 && Character.isLetterOrDigit(character)) {
                current.append(Character.toLowerCase(character));
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return List.copyOf(tokens);
    }

    private static int previousNonWhitespace(String value, int index) {
        int cursor = index;
        while (cursor >= 0 && Character.isWhitespace(value.charAt(cursor))) cursor--;
        return cursor;
    }

    private static int chineseDigit(char value) {
        return switch (value) {
            case '一' -> 1;
            case '二', '两', '俩' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            default -> 0;
        };
    }

    private static int englishCount(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "one" -> 1;
            case "two", "couple" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            default -> decimal(value);
        };
    }

    private static int decimal(String value) {
        if (value == null || value.isEmpty()) return 0;
        int parsed = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isDigit(character)) return 0;
            parsed = parsed * 10 + (character - '0');
        }
        return parsed;
    }

    private static void addIfSupported(int value, int maximum, Set<Integer> counts) {
        if (value >= 1 && value <= maximum) counts.add(value);
    }
}
