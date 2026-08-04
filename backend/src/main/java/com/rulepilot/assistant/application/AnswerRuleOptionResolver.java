package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleOptionRequest;
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates a complete, cited list of choices when the player explicitly asks what is available. */
final class AnswerRuleOptionResolver {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 8;
    private static final Pattern OPTION_REQUEST = Pattern.compile(
            "(?iu)\\b(?:what (?:are (?:the )?(?:options|ways|types|sources)|(?:special )?options|ways|types|sources)|"
                    + "what (?:can|may) (?:i|we|a player|the player) do|"
                    + "which (?:options|ways|types|sources|cards)|"
                    + "where can (?:i|we|a player|the player) (?:recruit|obtain|draw|take|get).{0,60}\\bfrom\\b|"
                    + "how many (?:ways|types|options)|available options|options (?:are|do)|list (?:the|all))\\b|"
                    + "有哪些|哪几种|什么选择|从哪里|有几种|可以做什么|能做什么|列出(?:全部|所有)");
    private static final Pattern EXPLICIT_COUNT = Pattern.compile(
            "(?iu)\\b(two|three|four|five|six|seven|eight|[2-8])\\s+"
                    + "(?:types?|ways?|options?|choices?|sources?|categories)\\b|"
                    + "(?:两|三|四|五|六|七|八)种(?:类型|方式|选择|来源)");
    private static final Pattern REPEATABLE = Pattern.compile(
            "(?iu)\\b(?:multiple times|more than once|repeat(?:ed|able)?|each time)\\b|多次|重复");
    private static final Pattern REQUIRED = Pattern.compile("(?iu)\\b(?:must|required)\\b|必须");
    private static final Pattern MANDATORY_OPTION_SET = Pattern.compile(
            "(?isu)\\bmust\\b.{0,200}\\b(?:one of|ways?|options?|choose|select|recruit)\\b|"
                    + "\\b(?:one of|ways?|options?|choose|select|recruit)\\b.{0,120}\\bmust\\b|"
                    + "必须.{0,80}(?:选择|一种|方式|招募)|(?:选择|一种|方式|招募).{0,80}必须");
    private static final Pattern ONE_SELECTION = Pattern.compile(
            "(?iu)\\b(?:one|exactly one|one of)\\b|一个|一种|三选一|四选一|任选其一");
    private static final Set<String> NAME_STOP_WORDS = Set.of(
            "a", "an", "any", "card", "cards", "from", "get", "of", "one", "the", "to", "use", "your");
    private static final Map<String, Integer> COUNTS = Map.ofEntries(
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("2", 2), Map.entry("3", 3), Map.entry("4", 4), Map.entry("5", 5),
            Map.entry("6", 6), Map.entry("7", 7), Map.entry("8", 8),
            Map.entry("两", 2), Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
            Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8));

    List<RuleOption> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("rule option input is invalid");
        if (draft.ruleOptions().isEmpty()) {
            if (asksForOptions(request.question())) {
                throw new IllegalArgumentException("option-list answer omitted cited choices");
            }
            return List.of();
        }
        if (draft.ruleOptions().size() < MIN_OPTIONS || draft.ruleOptions().size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("rule option count is invalid");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        List<RuleOption> resolved = draft.ruleOptions().stream()
                .map(item -> resolveOne(item, request, availableEvidence, answerCitations))
                .toList();
        validateSet(resolved, request);
        return resolved;
    }

    static boolean asksForOptions(String question) {
        return question != null && OPTION_REQUEST.matcher(question).find();
    }

    private RuleOption resolveOne(
            RuleOptionRequest item,
            ModelRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (item == null) throw new IllegalArgumentException("rule option item is null");
        bounded(item.decisionContext(), 240, "option decision context");
        bounded(item.selectionRule(), 400, "option selection rule");
        bounded(item.optionName(), 160, "option name");
        bounded(item.availabilityCondition(), 500, "option availability");
        bounded(item.result(), 700, "option result");
        RuleOptionBasis basis;
        try {
            basis = RuleOptionBasis.valueOf(item.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidBasis) {
            throw new IllegalArgumentException("rule option basis is invalid", invalidBasis);
        }
        if (item.citationIds() == null || item.citationIds().isEmpty() || item.citationIds().size() > 3) {
            throw new IllegalArgumentException("rule option citations are invalid");
        }
        List<UUID> citations = item.citationIds().stream().distinct().toList();
        if (citations.size() != item.citationIds().size()
                || !availableEvidence.containsAll(citations) || !answerCitations.containsAll(citations)) {
            throw new IllegalArgumentException("rule option cites evidence outside the answer scope");
        }
        String citedEvidence = citedEvidence(request, citations);
        if (!optionNameSupported(item.optionName(), citedEvidence)) {
            throw new IllegalArgumentException("rule option name is not present in cited evidence");
        }
        Set<String> supportedNumbers = numericTokens(citedEvidence);
        Set<String> claimedNumbers = numericTokens(String.join(" ", item.selectionRule(), item.optionName(),
                item.availabilityCondition(), item.result()));
        if (!supportedNumbers.containsAll(claimedNumbers)) {
            throw new IllegalArgumentException("rule option introduced an unsupported numeric rule");
        }
        return new RuleOption(item.decisionContext(), item.selectionRule(), item.optionName(),
                item.availabilityCondition(), item.result(), basis, citations);
    }

    private void validateSet(List<RuleOption> options, ModelRequest request) {
        RuleOption first = options.getFirst();
        if (options.stream().anyMatch(item -> !normalized(item.decisionContext())
                        .equals(normalized(first.decisionContext()))
                || !normalized(item.selectionRule()).equals(normalized(first.selectionRule()))
                || item.basis() != first.basis())) {
            throw new IllegalArgumentException("rule options do not describe one coherent choice set");
        }
        Set<String> names = new HashSet<>();
        if (options.stream().anyMatch(item -> !names.add(normalized(item.optionName())))) {
            throw new IllegalArgumentException("rule option names must be unique");
        }
        Set<UUID> citations = options.stream().flatMap(item -> item.citationIds().stream()).collect(Collectors.toSet());
        String evidence = citedEvidence(request, citations.stream().toList());
        Integer explicitCount = explicitCount(evidence);
        if (explicitCount != null && explicitCount != options.size()) {
            throw new IllegalArgumentException("rule option list does not match the cited explicit count");
        }
        if (first.basis() == RuleOptionBasis.SOURCE_SELECTION
                && !ONE_SELECTION.matcher(first.selectionRule()).find()) {
            throw new IllegalArgumentException("source selection omitted its one-source selection rule");
        }
        if ((first.basis() == RuleOptionBasis.SOURCE_SELECTION
                        || first.basis() == RuleOptionBasis.EXCLUSIVE_CHOICE)
                && MANDATORY_OPTION_SET.matcher(evidence).find()
                && !REQUIRED.matcher(first.selectionRule()).find()) {
            throw new IllegalArgumentException("rule option set omitted the cited mandatory selection");
        }
        if (first.basis() == RuleOptionBasis.ALTERNATIVE_ACTION && REPEATABLE.matcher(evidence).find()) {
            String card = first.selectionRule() + " " + options.stream().map(RuleOption::result)
                    .collect(Collectors.joining(" "));
            if (!REPEATABLE.matcher(card).find()) {
                throw new IllegalArgumentException("alternative actions omitted the cited repeatability");
            }
        }
    }

    private static Integer explicitCount(String evidence) {
        Matcher matcher = EXPLICIT_COUNT.matcher(evidence);
        if (!matcher.find()) return null;
        String token = matcher.group(1) == null
                ? matcher.group().substring(0, 1)
                : matcher.group(1).toLowerCase(Locale.ROOT);
        return COUNTS.get(token);
    }

    private static boolean optionNameSupported(String name, String evidence) {
        Set<String> words = Arrays.stream(normalized(name).split(" "))
                .filter(word -> word.length() > 2 && !NAME_STOP_WORDS.contains(word))
                .collect(Collectors.toSet());
        String normalizedEvidence = normalized(evidence);
        return words.isEmpty() ? normalizedEvidence.contains(normalized(name))
                : words.stream().allMatch(normalizedEvidence::contains);
    }

    private static String citedEvidence(ModelRequest request, List<UUID> citations) {
        return request.evidence().stream()
                .filter(evidence -> citations.contains(evidence.chunkId()))
                .map(EvidenceInput::excerpt)
                .collect(Collectors.joining(" "));
    }

    private static Set<String> numericTokens(String value) {
        if (value == null) return Set.of();
        return Arrays.stream(value.split("[^0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
