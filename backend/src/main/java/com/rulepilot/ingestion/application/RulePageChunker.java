package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.ingestion.application.RuleStructureRepository.DetectedRuleChunk;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Keeps retrieval evidence local to the page where a rule actually appears. */
@Component
public class RulePageChunker {

    static final int MAX_CHUNK_CHARACTERS = 1_800;
    private static final int MAX_HEADING_CHARACTERS = 160;
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("(?:\\R\\s*){2,}");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?。！？;；:：])\\s+");

    public List<DetectedRuleChunk> chunk(List<ExtractedPage> pages) {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("extracted pages are required");
        }
        List<DetectedRuleChunk> chunks = new ArrayList<>();
        for (ExtractedPage page : pages) {
            String text = page.text() == null ? "" : page.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            String heading = heading(text, page.pageNumber());
            String sectionType = weakSectionType(text);
            for (String content : split(text)) {
                chunks.add(new DetectedRuleChunk(sectionType, heading, content, page.pageNumber()));
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
}
