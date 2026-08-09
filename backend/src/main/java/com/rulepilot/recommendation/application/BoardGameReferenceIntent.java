package com.rulepilot.recommendation.application;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Finds an explicitly named comparison game without assigning any facts to it. */
@Component
@Profile("!test")
class BoardGameReferenceIntent {

    private static final Pattern QUOTED_COMPARISON = Pattern.compile(
            "(?iu)(?:类似(?:于)?|像|接近|similar\\s+to|(?:a|some|something|games?)\\s+like)"
                    + "[^《\"“]{0,12}[《\"“]([^》\"”]{2,80})[》\"”]");
    private static final Pattern REVERSED_QUOTED_COMPARISON = Pattern.compile(
            "(?iu)(?:和|跟)\\s*[《\"“]([^》\"”]{2,80})[》\"”]\\s*(?:类似|接近|差不多)");
    private static final Pattern NAMED_CORRECTION = Pattern.compile(
            "(?iu)(?:《([^》]{2,80})》|^([\\p{L}\\p{N}·:：'’\\- ]{2,80}?))\\s*(?:并)?不是");
    private static final Pattern CORRECTION = Pattern.compile(
            "(?iu)(?:不是|不对|说错|弄错|不了解|理解错|wrong|incorrect|do not understand|don't understand)");
    private static final Pattern GENERIC_REFERENCE = Pattern.compile(
            "(?iu)^(?:这|那|这个|那个|这款|那款)?(?:桌游|游戏)?$|^(?:it|this|that|this game|that game)$");

    Optional<ReferenceIntent> resolve(List<DialogueMessage> transcript, String latestMessage) {
        String latest = bounded(latestMessage);
        Optional<String> direct = title(latest);
        if (direct.isPresent()) return Optional.of(new ReferenceIntent(direct.orElseThrow(), latest, false));

        if (!CORRECTION.matcher(latest).find()) return Optional.empty();
        Optional<String> correctedTitle = correctedTitle(latest);
        if (correctedTitle.isPresent()) {
            return Optional.of(new ReferenceIntent(correctedTitle.orElseThrow(), latest, true));
        }
        if (transcript == null) return Optional.empty();
        for (int index = transcript.size() - 1; index >= 0; index--) {
            DialogueMessage message = transcript.get(index);
            if (message == null || !"user".equals(message.role())) continue;
            Optional<String> prior = title(message.text());
            if (prior.isPresent()) return Optional.of(new ReferenceIntent(prior.orElseThrow(), message.text(), true));
        }
        return Optional.empty();
    }

    Optional<ReferenceIntent> resolveAgent(
            String interpretedTitle,
            List<DialogueMessage> transcript,
            String latestMessage) {
        Optional<String> checked = checkedTitle(interpretedTitle);
        if (checked.isEmpty()) return Optional.empty();
        String title = checked.orElseThrow();
        boolean groundedInLatest = BoardGameTitleGrounding.occursInPlayerText(latestMessage, title);
        boolean groundedInTranscript = transcript != null && transcript.stream()
                .filter(message -> message != null && "user".equals(message.role()))
                .anyMatch(message -> BoardGameTitleGrounding.occursInPlayerText(message.text(), title));
        if (!groundedInLatest && !groundedInTranscript) return Optional.empty();
        return Optional.of(new ReferenceIntent(
                title,
                bounded(latestMessage),
                CORRECTION.matcher(bounded(latestMessage)).find()));
    }

    private Optional<String> title(String message) {
        return firstTitle(message, QUOTED_COMPARISON)
                .or(() -> firstTitle(message, REVERSED_QUOTED_COMPARISON));
    }

    private Optional<String> correctedTitle(String message) {
        Matcher matcher = NAMED_CORRECTION.matcher(message);
        if (!matcher.find()) return Optional.empty();
        String candidate = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        return checkedTitle(candidate);
    }

    private Optional<String> firstTitle(String message, Pattern pattern) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? checkedTitle(matcher.group(1)) : Optional.empty();
    }

    private Optional<String> checkedTitle(String value) {
        String title = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .strip();
        if (title.length() < 2 || title.length() > 80 || GENERIC_REFERENCE.matcher(title).matches()) {
            return Optional.empty();
        }
        return Optional.of(title);
    }

    private String bounded(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    record ReferenceIntent(String title, String basedOn, boolean correction) {
        ReferenceIntent {
            title = title.strip();
            basedOn = basedOn == null ? "" : basedOn.strip();
        }

        String normalizedTitle() {
            return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        }
    }
}
