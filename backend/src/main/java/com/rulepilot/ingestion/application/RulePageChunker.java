package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.ingestion.application.RuleStructureRepository.DetectedRuleChunk;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Keeps retrieval evidence local to the page where a rule actually appears. */
@Component
public class RulePageChunker {

    static final int MAX_CHUNK_CHARACTERS = 1_800;
    static final String VISUAL_PAGE_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
    private static final int MAX_HEADING_CHARACTERS = 160;
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("(?:\\R\\s*){2,}");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?。！？;；:：])\\s+");

    public List<DetectedRuleChunk> chunk(List<ExtractedPage> pages) {
        return chunk(pages, null);
    }

    List<DetectedRuleChunk> chunk(List<ExtractedPage> pages, RulebookUnderstanding understanding) {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("extracted pages are required");
        }
        List<DetectedRuleChunk> chunks = new ArrayList<>();
        for (ExtractedPage page : pages) {
            String text = page.text() == null ? "" : page.text().strip();
            if (text.isEmpty()) {
                chunks.add(new DetectedRuleChunk(
                        "GENERAL",
                        "Visual rulebook page " + page.pageNumber(),
                        VISUAL_PAGE_PLACEHOLDER,
                        page.pageNumber()));
                continue;
            }
            List<PageBlock> pageBlocks = understanding == null
                    ? List.of()
                    : understanding.pageBlocks().stream()
                            .filter(block -> block.pageNumber() == page.pageNumber())
                            .sorted(Comparator.comparingInt(PageBlock::readingOrder))
                            .toList();
            for (LocalSection section : localSections(text, page.pageNumber(), pageBlocks)) {
                String sectionType = weakSectionType(section.content());
                for (String content : split(section.content())) {
                    chunks.add(new DetectedRuleChunk(sectionType, section.heading(), content, page.pageNumber()));
                }
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("rulebook contains no extractable text");
        }
        return List.copyOf(chunks);
    }

    private List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            String normalized = paragraph.replaceAll("[\\t ]+", " ").strip();
            if (normalized.isEmpty()) {
                continue;
            }
            for (String unit : boundedUnits(normalized)) {
                if (!current.isEmpty() && current.length() + unit.length() + 2 > MAX_CHUNK_CHARACTERS) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(unit);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private List<LocalSection> localSections(String text, int pageNumber, List<PageBlock> pageBlocks) {
        if (pageBlocks.stream().anyMatch(block -> block.role() == BlockRole.HEADING)) {
            return positionedSections(text, pageNumber, pageBlocks);
        }
        return paragraphSections(text, pageNumber);
    }

    private List<LocalSection> positionedSections(String text, int pageNumber, List<PageBlock> pageBlocks) {
        List<LocalSection> sections = new ArrayList<>();
        String currentHeading = heading(text, pageNumber);
        StringBuilder current = new StringBuilder();
        for (PageBlock block : pageBlocks) {
            if (block.role() == BlockRole.FOOTER) continue;
            if (block.role() == BlockRole.HEADING) {
                if (!current.isEmpty()) {
                    sections.add(new LocalSection(currentHeading, current.toString()));
                    current.setLength(0);
                }
                currentHeading = boundedHeading(block.text());
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(block.text());
        }
        if (!current.isEmpty()) sections.add(new LocalSection(currentHeading, current.toString()));
        return sections.isEmpty()
                ? List.of(new LocalSection(heading(text, pageNumber), text))
                : List.copyOf(sections);
    }

    private List<LocalSection> paragraphSections(String text, int pageNumber) {
        List<LocalSection> sections = new ArrayList<>();
        String currentHeading = heading(text, pageNumber);
        StringBuilder current = new StringBuilder();
        for (String rawParagraph : PARAGRAPH_BREAK.split(text)) {
            String paragraph = rawParagraph.replaceAll("[\\t ]+", " ").strip();
            if (paragraph.isEmpty()) continue;
            String localHeading = headingAtParagraphStart(paragraph);
            if (localHeading != null) {
                if (!current.isEmpty()) {
                    sections.add(new LocalSection(currentHeading, current.toString()));
                    current.setLength(0);
                }
                currentHeading = localHeading;
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(paragraph);
        }
        if (!current.isEmpty()) sections.add(new LocalSection(currentHeading, current.toString()));
        return sections.isEmpty()
                ? List.of(new LocalSection(heading(text, pageNumber), text))
                : List.copyOf(sections);
    }

    private String headingAtParagraphStart(String paragraph) {
        String firstLine = paragraph.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        if (!looksLikeHeading(firstLine)) return null;
        return boundedHeading(firstLine);
    }

    private String boundedHeading(String heading) {
        String withoutColon = heading.replaceFirst("[：:]$", "").strip();
        return withoutColon.length() <= MAX_HEADING_CHARACTERS
                ? withoutColon
                : withoutColon.substring(0, MAX_HEADING_CHARACTERS).strip();
    }

    private boolean looksLikeHeading(String line) {
        String candidate = line == null ? "" : line.strip();
        if (candidate.length() < 2 || candidate.length() > MAX_HEADING_CHARACTERS
                || candidate.matches(".*[.!?。！？;,；，]$")) {
            return false;
        }
        String withoutColon = candidate.replaceFirst("[：:]$", "").strip();
        long letterCount = withoutColon.codePoints().filter(Character::isLetter).count();
        if (letterCount < 2) return false;
        if (withoutColon.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN)) {
            return withoutColon.codePointCount(0, withoutColon.length()) <= 32;
        }
        String letters = withoutColon.replaceAll("[^\\p{L}]", "");
        if (!letters.isBlank() && letters.equals(letters.toUpperCase(Locale.ROOT))) return true;
        List<String> significantWords = Arrays.stream(withoutColon.split("\\s+"))
                .map(word -> word.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", ""))
                .filter(word -> word.length() > 2)
                .filter(word -> !Set.of("and", "for", "from", "into", "the", "with").contains(
                        word.toLowerCase(Locale.ROOT)))
                .toList();
        return !significantWords.isEmpty()
                && significantWords.size() <= 10
                && significantWords.stream().allMatch(word -> Character.isUpperCase(word.codePointAt(0)));
    }

    private List<String> boundedUnits(String paragraph) {
        if (paragraph.length() <= MAX_CHUNK_CHARACTERS) {
            return List.of(paragraph);
        }
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_BREAK.split(paragraph)) {
            if (sentence.length() > MAX_CHUNK_CHARACTERS) {
                if (!current.isEmpty()) {
                    units.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < sentence.length(); start += MAX_CHUNK_CHARACTERS) {
                    units.add(sentence.substring(start, Math.min(sentence.length(), start + MAX_CHUNK_CHARACTERS)));
                }
            } else {
                if (!current.isEmpty() && current.length() + sentence.length() + 1 > MAX_CHUNK_CHARACTERS) {
                    units.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) current.append(' ');
                current.append(sentence);
            }
        }
        if (!current.isEmpty()) units.add(current.toString());
        return units;
    }

    private String heading(String text, int pageNumber) {
        String firstLine = Arrays.stream(text.split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("Rulebook page " + pageNumber);
        return firstLine.length() <= MAX_HEADING_CHARACTERS
                ? firstLine
                : firstLine.substring(0, MAX_HEADING_CHARACTERS).strip();
    }

    private String weakSectionType(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(LessonRuleSectionType.values())
                .filter(type -> type.keywords().stream().anyMatch(normalized::contains))
                .map(Enum::name)
                .findFirst()
                .orElse("GENERAL");
    }

    private record LocalSection(String heading, String content) {}
}
