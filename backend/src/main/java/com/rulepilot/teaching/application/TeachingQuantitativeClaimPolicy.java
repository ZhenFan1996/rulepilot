package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed structural checks for player-facing quantities before an untrusted lesson draft is published. */
final class TeachingQuantitativeClaimPolicy {

    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9])[-+]?\\d+(?:[.,]\\d+)?%?(?![A-Za-z0-9])");
    private static final Map<String, String> ENGLISH_NUMBER_VALUES = Map.ofEntries(
            Map.entry("zero", "0"),
            Map.entry("one", "1"),
            Map.entry("two", "2"),
            Map.entry("three", "3"),
            Map.entry("four", "4"),
            Map.entry("five", "5"),
            Map.entry("six", "6"),
            Map.entry("seven", "7"),
            Map.entry("eight", "8"),
            Map.entry("nine", "9"),
            Map.entry("ten", "10"),
            Map.entry("eleven", "11"),
            Map.entry("twelve", "12"),
            Map.entry("thirteen", "13"),
            Map.entry("fourteen", "14"),
            Map.entry("fifteen", "15"),
            Map.entry("sixteen", "16"),
            Map.entry("seventeen", "17"),
            Map.entry("eighteen", "18"),
            Map.entry("nineteen", "19"),
            Map.entry("twenty", "20"));
    private static final Pattern ENGLISH_NUMBER = Pattern.compile(
            "(?iu)\\b(" + String.join("|", ENGLISH_NUMBER_VALUES.keySet()) + ")\\b");
    private static final Map<String, String> ENGLISH_ORDINAL_VALUES = Map.ofEntries(
            Map.entry("first", "1"),
            Map.entry("second", "2"),
            Map.entry("third", "3"),
            Map.entry("fourth", "4"),
            Map.entry("fifth", "5"),
            Map.entry("sixth", "6"),
            Map.entry("seventh", "7"),
            Map.entry("eighth", "8"),
            Map.entry("ninth", "9"),
            Map.entry("tenth", "10"),
            Map.entry("eleventh", "11"),
            Map.entry("twelfth", "12"),
            Map.entry("thirteenth", "13"),
            Map.entry("fourteenth", "14"),
            Map.entry("fifteenth", "15"),
            Map.entry("sixteenth", "16"),
            Map.entry("seventeenth", "17"),
            Map.entry("eighteenth", "18"),
            Map.entry("nineteenth", "19"),
            Map.entry("twentieth", "20"));
    private static final Pattern ENGLISH_ORDINAL = Pattern.compile(
            "(?iu)\\b(" + String.join("|", ENGLISH_ORDINAL_VALUES.keySet()) + ")\\b");
    private static final Pattern CHINESE_AMOUNT = Pattern.compile(
            "(?<!另)([零〇一二两三四五六七八九十]{1,3})(?=\\s*(?:个|张|枚|块|种|位|名|局|轮|回合|次|倍|格|分|点|份|组|类|项|条))");
    private static final Pattern TEACHING_ENUMERATION_COUNT = Pattern.compile(
            "(?iu)([零〇一二两三四五六七八九十]{1,3}|\\d{1,2}|"
                    + String.join("|", ENGLISH_NUMBER_VALUES.keySet())
                    + ")\\s*(?:个)?(?:步骤|触发条件|条件|件事|分支|选项|steps?|conditions?|items?|branches?|options?)");
    private static final Pattern CONDITIONAL_SENTENCE = Pattern.compile("(?iu)[^.。;；\\n]*\\bor if\\b[^.。;；\\n]*");
    private static final Pattern IF_WORD = Pattern.compile("(?iu)\\bif\\b");
    private static final Pattern COLON_LIST = Pattern.compile("(?iu)[:：]\\s*([^.。;；\\n]{1,500})");
    private static final Pattern PRODUCT = Pattern.compile(
            "(?iu)(?:\\b(?:multiply|multiplied|multiplier|product)\\b|[×*]|(?<=\\d)\\s*[xX]\\s*(?=\\d)|乘以|相乘|倍)");
    private static final Pattern ENGLISH_REPETITION = Pattern.compile(
            "(?iu)\\b(?:each|every|per|again|once)\\b");
    private static final Pattern CHINESE_REPETITION = Pattern.compile("每|各|逐|一次|再算|重复");

    private TeachingQuantitativeClaimPolicy() {}

    static void validate(
            TeachingPlan.PlannedSection planned,
            SectionDraft draft,
            List<Claim> claims,
            Map<UUID, RuleEvidence> allowedEvidence) {
        if (planned == null || draft == null || claims == null || allowedEvidence == null) {
            throw new IllegalArgumentException("quantitative teaching validation input is required");
        }
        boolean citedEvidenceContainsProduct = claims.stream()
                .flatMap(claim -> claim.citationIds().stream())
                .map(allowedEvidence::get)
                .filter(java.util.Objects::nonNull)
                .map(RuleEvidence::excerpt)
                .anyMatch(value -> PRODUCT.matcher(value).find());
        boolean playerTextContainsProduct = claims.stream()
                .map(Claim::text)
                .anyMatch(value -> PRODUCT.matcher(value).find());
        boolean aggregationSection = planned.coverageTags().stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch("scoring"::equals)
                || draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.LEDGER);
        if (!aggregationSection && !citedEvidenceContainsProduct && !playerTextContainsProduct) return;

        boolean productScopePreserved = false;
        boolean hasQuantitativeClaim = false;

        for (Claim claim : claims) {
            String playerText = playerStatement(claim.text());
            Set<String> claimNumbers = numbers(playerText);
            List<RuleEvidence> cited = claim.citationIds().stream()
                    .map(allowedEvidence::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            boolean citedClaimContainsProduct = cited.stream()
                    .map(RuleEvidence::excerpt)
                    .anyMatch(value -> PRODUCT.matcher(value).find());
            boolean playerClaimContainsProduct = PRODUCT.matcher(playerText).find();
            int claimRepetitionMarkers = repetitionMarkers(playerText);
            boolean quantitative = !claimNumbers.isEmpty()
                    || citedClaimContainsProduct
                    || playerClaimContainsProduct
                    || claimRepetitionMarkers > 0;
            if (!quantitative) continue;
            hasQuantitativeClaim = true;

            Set<String> evidenceNumbers = new LinkedHashSet<>();
            for (RuleEvidence source : cited) {
                evidenceNumbers.addAll(numbers(source.excerpt()));
            }
            if (!evidenceNumbers.containsAll(claimNumbers)) {
                Set<String> unsupported = new LinkedHashSet<>(claimNumbers);
                unsupported.removeAll(evidenceNumbers);
                Set<String> teachingEnumerationCounts = teachingEnumerationCounts(playerText);
                Set<String> citedEnumerationCounts = cited.stream()
                        .flatMap(source -> evidenceEnumerationCounts(source.excerpt()).stream())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                unsupported.removeIf(value ->
                        teachingEnumerationCounts.contains(value) && citedEnumerationCounts.contains(value));
                if (unsupported.isEmpty()) continue;
                throw new UnsupportedQuantitativeClaimException(
                        claim.position(),
                        claimNumbers,
                        unsupported,
                        "Quantitative teaching claim at claim position " + claim.position()
                                + " introduces unsupported value(s): " + unsupported);
            }
            if (playerClaimContainsProduct && !citedClaimContainsProduct) {
                throw new IllegalArgumentException("Quantitative teaching claim introduces an unsupported multiplier.");
            }
            if (citedClaimContainsProduct
                    && (playerClaimContainsProduct || claimRepetitionMarkers >= 2)) {
                productScopePreserved = true;
            }
        }

        if (!hasQuantitativeClaim) return;
        if (citedEvidenceContainsProduct && !productScopePreserved) {
            throw new IllegalArgumentException(
                    "Quantitative teaching claim omits the source multiplier or repeated aggregation scope.");
        }
    }

    private static String playerStatement(String claim) {
        String normalized = normalize(claim);
        int separator = normalized.indexOf(':');
        return separator >= 0 ? normalized.substring(separator + 1).strip() : normalized;
    }

    static Set<String> numbers(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(normalize(value));
        while (matcher.find()) {
            String raw = matcher.group().replace("%", "").replace(",", "");
            try {
                result.add(new BigDecimal(raw).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ignored) {
                // The regex is deliberately conservative. An unparseable match is not treated as a verified value.
            }
        }
        Matcher english = ENGLISH_NUMBER.matcher(normalize(value));
        while (english.find()) result.add(ENGLISH_NUMBER_VALUES.get(english.group(1).toLowerCase(Locale.ROOT)));
        Matcher ordinal = ENGLISH_ORDINAL.matcher(normalize(value));
        while (ordinal.find()) result.add(ENGLISH_ORDINAL_VALUES.get(ordinal.group(1).toLowerCase(Locale.ROOT)));
        Matcher chinese = CHINESE_AMOUNT.matcher(normalize(value));
        while (chinese.find()) {
            Integer parsed = chineseNumber(chinese.group(1));
            if (parsed != null) result.add(String.valueOf(parsed));
        }
        return Set.copyOf(result);
    }

    private static Integer chineseNumber(String value) {
        int ten = value.indexOf('十');
        if (ten < 0) return value.length() == 1 ? chineseDigit(value.charAt(0)) : null;
        Integer tens = ten == 0 ? 1 : chineseDigit(value.charAt(0));
        Integer ones = ten == value.length() - 1 ? 0 : chineseDigit(value.charAt(ten + 1));
        return tens == null || ones == null ? null : tens * 10 + ones;
    }

    private static Integer chineseDigit(char value) {
        return switch (value) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> null;
        };
    }

    private static int repetitionMarkers(String value) {
        return matches(ENGLISH_REPETITION, value) + matches(CHINESE_REPETITION, value);
    }

    /**
     * A count of explicitly listed teaching items is a deterministic summary, not a new game quantity. It is accepted
     * only when the same cited excerpt visibly contains exactly that many list items or conditional alternatives.
     */
    private static Set<String> teachingEnumerationCounts(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = TEACHING_ENUMERATION_COUNT.matcher(normalize(value));
        while (matcher.find()) {
            String parsed = countValue(matcher.group(1));
            if (parsed != null) result.add(parsed);
        }
        return Set.copyOf(result);
    }

    private static Set<String> evidenceEnumerationCounts(String value) {
        String normalized = normalize(value);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        long bullets = normalized.codePoints().filter(codePoint -> codePoint == '•').count();
        if (bullets >= 2 && bullets <= 20) result.add(String.valueOf(bullets));

        Matcher conditional = CONDITIONAL_SENTENCE.matcher(normalized);
        while (conditional.find()) {
            int alternatives = matches(IF_WORD, conditional.group());
            if (alternatives >= 2 && alternatives <= 20) result.add(String.valueOf(alternatives));
        }

        Matcher list = COLON_LIST.matcher(normalized);
        while (list.find()) {
            String fragment = list.group(1).strip();
            if (!fragment.contains(",") && !fragment.contains("、") && !fragment.contains(" and ")) continue;
            long items = java.util.Arrays.stream(fragment.split("\\s*,\\s*|、|\\s+and\\s+"))
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .count();
            if (items >= 2 && items <= 20) result.add(String.valueOf(items));
        }
        return Set.copyOf(result);
    }

    private static String countValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = normalize(raw);
        if (ENGLISH_NUMBER_VALUES.containsKey(normalized)) return ENGLISH_NUMBER_VALUES.get(normalized);
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Integer chinese = chineseNumber(normalized);
        return chinese == null ? null : String.valueOf(chinese);
    }

    private static int matches(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) count++;
        return count;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ")
                        .strip();
    }

    static final class UnsupportedQuantitativeClaimException extends IllegalArgumentException {
        private final int claimPosition;
        private final Set<String> claimNumbers;
        private final Set<String> unsupportedValues;

        UnsupportedQuantitativeClaimException(int claimPosition, String message) {
            this(claimPosition, Set.of(), Set.of(), message);
        }

        UnsupportedQuantitativeClaimException(
                int claimPosition,
                Set<String> claimNumbers,
                Set<String> unsupportedValues,
                String message) {
            super(message);
            this.claimPosition = claimPosition;
            this.claimNumbers = Set.copyOf(claimNumbers);
            this.unsupportedValues = Set.copyOf(unsupportedValues);
        }

        int claimPosition() {
            return claimPosition;
        }

        Set<String> claimNumbers() {
            return claimNumbers;
        }

        Set<String> unsupportedValues() {
            return unsupportedValues;
        }
    }
}
