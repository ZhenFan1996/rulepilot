package com.rulepilot.recommendation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Structural publication gate for plain-text player messages; it does not decide product semantics. */
public final class PlayerFacingMessagePolicy {

    private static final Map<Character, Character> CLOSING_FOR = Map.ofEntries(
            Map.entry('(', ')'),
            Map.entry('（', '）'),
            Map.entry('[', ']'),
            Map.entry('【', '】'),
            Map.entry('{', '}'),
            Map.entry('《', '》'),
            Map.entry('「', '」'),
            Map.entry('『', '』'),
            Map.entry('“', '”'),
            Map.entry('‘', '’'));
    private static final Set<Character> CLOSING = Set.copyOf(CLOSING_FOR.values());
    private static final Set<Integer> DANGLING_ENDINGS = Set.of(
            (int) ',',
            (int) '，',
            (int) ':',
            (int) '：',
            (int) ';',
            (int) '；',
            (int) '-',
            (int) '—',
            (int) '/',
            (int) '、');
    private static final Set<Integer> TERMINAL_ENDINGS = Set.of(
            (int) '.',
            (int) '。',
            (int) '!',
            (int) '！',
            (int) '?',
            (int) '？',
            (int) '…',
            (int) ')',
            (int) '）',
            (int) ']',
            (int) '】',
            (int) '}',
            (int) '》',
            (int) '」',
            (int) '』',
            (int) '”',
            (int) '’');

    private PlayerFacingMessagePolicy() {}

    public static Optional<Issue> issue(String text, Purpose purpose) {
        if (text == null || text.isBlank()) return Optional.of(Issue.INCOMPLETE);
        String checked = text.strip();
        if (containsRawMarkup(checked)) return Optional.of(Issue.RAW_MARKUP);
        if (!balancedDelimiters(checked)) return Optional.of(Issue.UNBALANCED_DELIMITER);
        int last = checked.codePointBefore(checked.length());
        if (DANGLING_ENDINGS.contains(last) || CLOSING_FOR.containsKey((char) last)) {
            return Optional.of(Issue.INCOMPLETE);
        }
        if (purpose == Purpose.QUESTION) {
            long questionMarks = checked.codePoints()
                    .filter(value -> value == '?' || value == '？')
                    .count();
            if (questionMarks != 1) return Optional.of(Issue.INCOMPLETE);
        }
        if (checked.codePointCount(0, checked.length()) >= 40
                && !TERMINAL_ENDINGS.contains(last)
                && Character.getType(last) != Character.OTHER_SYMBOL) {
            return Optional.of(Issue.INCOMPLETE);
        }
        return Optional.empty();
    }

    private static boolean containsRawMarkup(String text) {
        if (text.contains("```")
                || text.indexOf('`') >= 0
                || text.contains("**")
                || text.contains("__")
                || text.contains("](")) {
            return true;
        }
        return text.lines().map(String::stripLeading).anyMatch(PlayerFacingMessagePolicy::markupLine);
    }

    private static boolean markupLine(String line) {
        if (line.startsWith("# ") || line.startsWith("## ") || line.startsWith("- ") || line.startsWith("* ")) {
            return true;
        }
        int cursor = 0;
        while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) cursor++;
        return cursor > 0
                && cursor + 1 < line.length()
                && line.charAt(cursor) == '.'
                && line.charAt(cursor + 1) == ' ';
    }

    private static boolean balancedDelimiters(String text) {
        Deque<Character> expected = new ArrayDeque<>();
        int asciiQuoteCount = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '"') asciiQuoteCount++;
            Character closing = CLOSING_FOR.get(character);
            if (closing != null) {
                expected.push(closing);
            } else if (CLOSING.contains(character)
                    && (expected.isEmpty() || expected.pop() != character)) {
                return false;
            }
        }
        return expected.isEmpty() && asciiQuoteCount % 2 == 0;
    }

    public enum Purpose {
        CONVERSATION,
        QUESTION,
        RECOMMENDATION_CONNECTIVE
    }

    public enum Issue {
        RAW_MARKUP,
        UNBALANCED_DELIMITER,
        INCOMPLETE
    }
}
