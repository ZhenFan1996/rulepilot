package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Pure cross-page visual-evidence rules for an untrusted player-facing answer draft. */
final class AnswerVisualEvidencePolicy {

    private static final Pattern VISUAL_IDENTITY_QUESTION = Pattern.compile(
            "(?iu)\\b(?:which|what)\\s+(?:resource|token|icon|symbol)\\b"
                    + "|\\b(?:resource|token|icon|symbol)\\b.{0,36}\\b(?:mean|represent|refer|correspond)\\b"
                    + "|\\b(?:pay|cost|spend)\\b.{0,36}\\b(?:resource|token|icon|symbol)\\b"
                    + "|(?:哪个|哪种|哪一种|什么|何种).{0,18}(?:资源|令牌|标记|图标|符号|胜利点)"
                    + "|(?:图标|符号).{0,36}(?:表示|代表|对应|是什么|含义)"
                    + "|(?:支付|花费|消耗|获得).{0,18}(?:什么|哪种|哪一种|何种).{0,18}(?:资源|令牌|标记|图标|符号|胜利点)");
    private static final Pattern VISUAL_IDENTITY_ASSERTION = Pattern.compile(
            "(?iu)(?:\\b(?:icon|symbol|pictograph)\\b.{0,36}\\b(?:means|represents|corresponds|refers)\\b"
                    + "|(?:图标|符号).{0,36}(?:表示|代表|对应|含义|是))");
    private static final Pattern EVIDENCED_CROSS_PAGE_ICON_MAPPING = Pattern.compile(
            "(?iu)(?:visually identical|same icon|exact visual match|视觉完全相同|同一图标)"
                    + ".{0,240}(?:labeled|component name|标为|标签|组件名)");
    private static final Pattern RESOLVED_VISUAL_COMPONENT = Pattern.compile(
            "(?iu)(?:visually identical|same icon|exact visual match|视觉完全相同|同一图标)"
                    + ".{0,160}?labeled\\s*['\"“]([^'\"”\\n]{2,80})['\"”]");
    private static final Pattern RESOLVED_VISUAL_REFERENCE_PAGE = Pattern.compile(
            "(?iu)(?:visually identical|same icon|exact visual match|视觉完全相同|同一图标)"
                    + ".{0,280}?on page\\s*(\\d{1,4})");

    private AnswerVisualEvidencePolicy() {}

    static boolean requiresIdentityReconciliation(ModelRequest request, ModelDraft draft) {
        boolean identityQuestion = VISUAL_IDENTITY_QUESTION.matcher(request.question()).find();
        boolean identityAssertion = VISUAL_IDENTITY_ASSERTION.matcher(playerFacingText(draft)).find();
        if (!identityQuestion && !identityAssertion) {
            return false;
        }
        if (hasEvidencedCrossPageIconMapping(request)) {
            return false;
        }
        Set<UUID> cited = Set.copyOf(draft.citationIds());
        return request.evidence().stream()
                .filter(source -> identityQuestion || cited.contains(source.chunkId()))
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(excerpt -> excerpt.contains("Visual page facts")
                        && AnswerEvidencePolicy.hasUnresolvedVisualSymbol(excerpt));
    }

    static boolean hasEvidencedCrossPageIconMapping(ModelRequest request) {
        long distinctPages = request.evidence().stream()
                .flatMapToInt(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                .distinct()
                .limit(2)
                .count();
        return distinctPages >= 2 && request.evidence().stream()
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(excerpt -> EVIDENCED_CROSS_PAGE_ICON_MAPPING.matcher(excerpt).find());
    }

    static List<String> resolvedComponents(ModelRequest request, ModelDraft draft) {
        LinkedHashSet<String> components = new LinkedHashSet<>();
        applicableMappingEvidence(request, draft)
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .forEach(excerpt -> {
                    var matcher = RESOLVED_VISUAL_COMPONENT.matcher(excerpt);
                    while (matcher.find()) components.add(matcher.group(1).strip());
                });
        return List.copyOf(components);
    }

    static boolean namesEveryResolvedComponent(ModelRequest request, ModelDraft draft) {
        String verdict = draft.shortVerdict() == null ? "" : draft.shortVerdict().toLowerCase(Locale.ROOT);
        return resolvedComponents(request, draft).stream()
                .allMatch(component -> verdict.contains(component.toLowerCase(Locale.ROOT)));
    }

    static ModelDraft includeReferenceCitations(ModelRequest request, ModelDraft draft) {
        LinkedHashSet<Integer> referencePages = new LinkedHashSet<>();
        applicableMappingEvidence(request, draft)
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .forEach(excerpt -> {
                    var matcher = RESOLVED_VISUAL_REFERENCE_PAGE.matcher(excerpt);
                    while (matcher.find()) referencePages.add(Integer.parseInt(matcher.group(1)));
                });
        if (referencePages.isEmpty()) return draft;
        LinkedHashSet<UUID> citations = new LinkedHashSet<>(draft.citationIds());
        for (Integer page : referencePages) {
            boolean alreadyCovered = request.evidence().stream()
                    .filter(source -> citations.contains(source.chunkId()))
                    .anyMatch(source -> source.pageFrom() <= page && source.pageTo() >= page);
            if (alreadyCovered) continue;
            request.evidence().stream()
                    .filter(source -> source.pageFrom() <= page && source.pageTo() >= page)
                    .findFirst()
                    .map(EvidenceInput::chunkId)
                    .ifPresent(citations::add);
        }
        if (citations.size() == draft.citationIds().size()) return draft;
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                draft.shortVerdict(),
                draft.explanation(),
                List.copyOf(citations),
                draft.exceptions(),
                draft.confidence(),
                draft.answerBasis());
    }

    private static Stream<EvidenceInput> applicableMappingEvidence(ModelRequest request, ModelDraft draft) {
        boolean visualIdentityQuestion = VISUAL_IDENTITY_QUESTION.matcher(request.question()).find();
        Set<UUID> cited = Set.copyOf(draft.citationIds());
        return request.evidence().stream()
                .filter(source -> visualIdentityQuestion || cited.contains(source.chunkId()));
    }

    private static String playerFacingText(ModelDraft draft) {
        return Stream.concat(
                        Stream.of(draft.shortVerdict(), draft.explanation()),
                        draft.exceptions().stream())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
