package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionProposer.Proposal;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Builds bounded, application-owned crop candidates from source layout only. */
@Component
public final class VisualRegionCandidateSelector {

    private static final Rectangle WHOLE_PAGE = new Rectangle(0, 0, 1_000, 1_000);
    private static final int MIN_REGION_SIZE = 20;
    private static final int TILE_SIZE = 550;
    private static final int SECOND_TILE_ORIGIN = 1_000 - TILE_SIZE;

    /** sectionTerms is validation-only: player or model prose must never steer crop geometry. */
    public List<Candidate> select(
            RulebookUnderstanding understanding,
            Set<Integer> citedPages,
            List<String> sectionTerms) {
        return select(understanding, citedPages, sectionTerms, Map.of());
    }

    /**
     * Adds pixel-derived geometry without letting prose steer it. Proposal order is preserved and interleaved with
     * PDF layout and coarse coverage, so one imperfect geometry source cannot consume the first attachment batch.
     */
    public List<Candidate> select(
            RulebookUnderstanding understanding,
            Set<Integer> citedPages,
            List<String> sectionTerms,
            Map<Integer, List<Proposal>> visualProposals) {
        if (understanding == null || citedPages == null || sectionTerms == null || visualProposals == null) {
            throw new IllegalArgumentException("visual region selection input is required");
        }
        if (citedPages.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("visual region cited pages are invalid");
        }
        if (citedPages.isEmpty()) return List.of();

        List<Integer> pages = citedPages.stream().sorted().toList();
        Map<Integer, List<Candidate>> byPage = new LinkedHashMap<>();
        pages.forEach(page -> byPage.put(
                page,
                candidatesForPage(understanding, page, visualProposals.getOrDefault(page, List.of()))));

        List<Candidate> selected = new ArrayList<>();
        for (int index = 0; ; index++) {
            boolean added = false;
            for (int page : pages) {
                List<Candidate> pageCandidates = byPage.get(page);
                if (index < pageCandidates.size()) {
                    selected.add(pageCandidates.get(index));
                    added = true;
                }
            }
            if (!added) break;
        }
        return List.copyOf(selected);
    }

    private List<Candidate> candidatesForPage(
            RulebookUnderstanding understanding, int pageNumber, List<Proposal> visualProposals) {
        List<Candidate> detectedRegions = visualProposals.stream()
                .map(Proposal::rectangle)
                .filter(rectangle -> !WHOLE_PAGE.equals(rectangle))
                .filter(rectangle -> rectangle.width() >= MIN_REGION_SIZE
                        && rectangle.height() >= MIN_REGION_SIZE)
                .map(rectangle -> candidate(pageNumber, rectangle, VisualSourceKind.PAGE_REGION))
                .toList();
        List<Candidate> tiles = pageTiles(pageNumber);
        List<Candidate> nativeBlocks = understanding.pageBlocks().stream()
                .filter(block -> block.pageNumber() == pageNumber)
                .filter(block -> block.role() != BlockRole.FOOTER)
                .filter(block -> !WHOLE_PAGE.equals(block.rectangle()))
                .filter(block -> block.rectangle().width() >= MIN_REGION_SIZE
                        && block.rectangle().height() >= MIN_REGION_SIZE)
                .sorted(Comparator.comparingInt(RulebookUnderstanding.PageBlock::readingOrder)
                        .thenComparingInt(RulebookUnderstanding.PageBlock::blockIndex))
                .map(block -> candidate(pageNumber, block.rectangle(), VisualSourceKind.PAGE_REGION))
                .toList();

        Map<Rectangle, Candidate> candidates = new LinkedHashMap<>();
        // Native layout, pixel geometry and four coarse coverage crops are independent candidate sources. Round-robin
        // ordering keeps all three visible in an early bounded attachment batch; none can starve the others.
        int count = Math.max(detectedRegions.size(), Math.max(tiles.size(), nativeBlocks.size()));
        for (int index = 0; index < count; index++) {
            if (index < detectedRegions.size()) {
                Candidate proposal = detectedRegions.get(index);
                candidates.putIfAbsent(proposal.rectangle(), proposal);
            }
            if (index < tiles.size()) {
                Candidate tile = tiles.get(index);
                candidates.putIfAbsent(tile.rectangle(), tile);
            }
            if (index < nativeBlocks.size()) {
                Candidate block = nativeBlocks.get(index);
                candidates.putIfAbsent(block.rectangle(), block);
            }
        }
        return List.copyOf(candidates.values());
    }

    private List<Candidate> pageTiles(int pageNumber) {
        List<Candidate> tiles = new ArrayList<>(4);
        for (int y : List.of(0, SECOND_TILE_ORIGIN)) {
            for (int x : List.of(0, SECOND_TILE_ORIGIN)) {
                tiles.add(candidate(
                        pageNumber,
                        new Rectangle(x, y, TILE_SIZE, TILE_SIZE),
                        VisualSourceKind.PAGE_REGION));
            }
        }
        return List.copyOf(tiles);
    }

    private Candidate candidate(int pageNumber, Rectangle rectangle, VisualSourceKind sourceKind) {
        return new Candidate(candidateId(pageNumber, rectangle, sourceKind), pageNumber, rectangle, sourceKind);
    }

    private String candidateId(int pageNumber, Rectangle rectangle, VisualSourceKind sourceKind) {
        String structuralIdentity = "%d:%d:%d:%d:%d:%s".formatted(
                pageNumber,
                rectangle.x(),
                rectangle.y(),
                rectangle.width(),
                rectangle.height(),
                sourceKind.name());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(structuralIdentity.getBytes(StandardCharsets.UTF_8));
            return "vc_" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Candidate(
            String candidateId,
            int pageNumber,
            Rectangle rectangle,
            VisualSourceKind sourceKind) {
        public Candidate {
            if (candidateId == null
                    || !candidateId.matches("[A-Za-z0-9_-]{4,64}")
                    || pageNumber < 1
                    || rectangle == null
                    || sourceKind == null) {
                throw new IllegalArgumentException("visual region candidate is invalid");
            }
            boolean wholePage = rectangle.equals(WHOLE_PAGE);
            if ((sourceKind == VisualSourceKind.FULL_PAGE) != wholePage) {
                throw new IllegalArgumentException("visual candidate kind and geometry must agree");
            }
        }
    }
}
