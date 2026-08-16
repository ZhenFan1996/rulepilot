package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SourceLanguageRetrievalPolicy {

    private static final Pattern LATIN_TERM = Pattern.compile("[A-Za-z][A-Za-z'-]{2,}");

    private SourceLanguageRetrievalPolicy() {}

    public static void validate(OutlineRequest request, OutlineDraft outline) {
        if (outline != null && !outline.sourceCoverageSlots().isEmpty()) {
            // Topic queries are optional model hints once the outline owns a canonical source ledger. Revalidate that
            // ledger here before ignoring query wording so translated player-facing planning cannot weaken the exact
            // source-identifier boundary.
            TeachingSourceCoverageContract.requireCompleteSourceContract(request, outline);
            return;
        }
        if (!isLatinDominant(request)) return;
        String source = request.pages().stream()
                .map(page -> page.text().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        var invalidTopics = outline.topics().stream()
                .filter(topic -> topic.retrievalQueries().stream()
                        .anyMatch(query -> !containsSourceTerm(query, source)))
                .map(topic -> topic.key())
                .toList();
        if (!invalidTopics.isEmpty()) {
            throw new IllegalArgumentException(
                    "retrieval queries must preserve exact source-language terms for topics " + invalidTopics);
        }
    }

    private static boolean isLatinDominant(OutlineRequest request) {
        long latin = request.pages().stream()
                .flatMapToInt(page -> page.text().chars())
                .filter(SourceLanguageRetrievalPolicy::latinLetter)
                .count();
        long han = request.pages().stream()
                .flatMapToInt(page -> page.text().chars())
                .filter(SourceLanguageRetrievalPolicy::hanCharacter)
                .count();
        return latin >= 200 && latin >= han * 2;
    }

    private static boolean containsSourceTerm(String query, String source) {
        return LATIN_TERM.matcher(query).results()
                .map(result -> result.group().toLowerCase(Locale.ROOT))
                .filter(term -> term.length() >= 4)
                .anyMatch(source::contains);
    }

    private static boolean latinLetter(int character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    private static boolean hanCharacter(int character) {
        return Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN;
    }
}
