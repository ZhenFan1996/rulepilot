package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.RuleStructureCatalog;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.ingestion.application.RuleStructureRepository.DetectedRuleSection;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RuleStructureService implements RuleStructureCatalog, RulebookUnderstandingCatalog {

    private final RuleStructureClassifier classifier;
    private final RulePageChunker chunker;
    private final RulebookUnderstandingBuilder understandingBuilder;
    private final RuleStructureRepository repository;

    public RuleStructureService(
            RuleStructureClassifier classifier,
            RulePageChunker chunker,
            RulebookUnderstandingBuilder understandingBuilder,
            RuleStructureRepository repository) {
        this.classifier = classifier;
        this.chunker = chunker;
        this.understandingBuilder = understandingBuilder;
        this.repository = repository;
    }

    @Transactional
    public void organize(UUID documentVersionId, List<DocumentProcessing.ExtractedPage> pages) {
        repository.replace(
                documentVersionId,
                classifier.classify(pages),
                chunker.chunk(pages),
                understandingBuilder.build(pages));
    }

    @Transactional(readOnly = true)
    @Override
    public StructureView structure(UUID documentVersionId) {
        Map<LessonRuleSectionType, DetectedRuleSection> detected = repository.findByDocumentVersion(documentVersionId)
                .stream()
                .collect(Collectors.toMap(DetectedRuleSection::type, Function.identity()));
        List<SectionView> sections = Arrays.stream(LessonRuleSectionType.values())
                .map(type -> sectionView(type, detected.get(type)))
                .toList();
        long present = sections.stream().filter(SectionView::present).count();
        return new StructureView(sections, (int) present, sections.size());
    }

    @Override
    @Transactional(readOnly = true)
    public com.rulepilot.ingestion.layout.RulebookUnderstanding understanding(UUID documentVersionId) {
        return repository.findUnderstanding(documentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("rulebook understanding does not exist"));
    }

    private SectionView sectionView(LessonRuleSectionType type, DetectedRuleSection detected) {
        return detected == null
                ? new SectionView(type.name(), type.label(), false, "", List.of())
                : new SectionView(type.name(), type.label(), true, detected.content(), detected.pageNumbers());
    }
}
