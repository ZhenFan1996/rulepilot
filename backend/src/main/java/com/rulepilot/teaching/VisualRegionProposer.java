package com.rulepilot.teaching;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import java.time.Duration;
import java.util.List;

/**
 * A local geometry tool that proposes immutable page regions before a vision model chooses semantic relevance.
 *
 * <p>The tool may inspect pixels, but it never interprets rules and never receives player or model prose. Geometry
 * therefore remains application-owned: the vision model can select an opaque candidate id, but cannot author or
 * refine coordinates.</p>
 */
public interface VisualRegionProposer {

    ProposalResult propose(DocumentPageImages.PageImage page, Duration timeout);

    default boolean configured() {
        return true;
    }

    static VisualRegionProposer unavailable() {
        return new VisualRegionProposer() {
            @Override
            public ProposalResult propose(DocumentPageImages.PageImage page, Duration timeout) {
                return ProposalResult.unavailable();
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }

    enum Diagnostic {
        FOUND,
        NONE,
        UNAVAILABLE,
        TIMEOUT,
        FAILED
    }

    record Proposal(Rectangle rectangle) {
        public Proposal {
            if (rectangle == null
                    || rectangle.x() < 0
                    || rectangle.y() < 0
                    || rectangle.width() < 20
                    || rectangle.height() < 20
                    || rectangle.x() + rectangle.width() > 1_000
                    || rectangle.y() + rectangle.height() > 1_000
                    || (rectangle.x() == 0
                            && rectangle.y() == 0
                            && rectangle.width() == 1_000
                            && rectangle.height() == 1_000)) {
                throw new IllegalArgumentException("visual region proposal is invalid");
            }
        }
    }

    record ProposalResult(List<Proposal> proposals, Diagnostic diagnostic) {
        public ProposalResult {
            if (proposals == null
                    || diagnostic == null
                    || (proposals.isEmpty() && diagnostic == Diagnostic.FOUND)
                    || (!proposals.isEmpty() && diagnostic != Diagnostic.FOUND)) {
                throw new IllegalArgumentException("visual region proposal result is invalid");
            }
            proposals = List.copyOf(proposals);
        }

        public static ProposalResult found(List<Proposal> proposals) {
            if (proposals == null || proposals.isEmpty()) {
                throw new IllegalArgumentException("found visual regions are required");
            }
            return new ProposalResult(proposals, Diagnostic.FOUND);
        }

        public static ProposalResult none() {
            return new ProposalResult(List.of(), Diagnostic.NONE);
        }

        public static ProposalResult unavailable() {
            return new ProposalResult(List.of(), Diagnostic.UNAVAILABLE);
        }

        public static ProposalResult timeout() {
            return new ProposalResult(List.of(), Diagnostic.TIMEOUT);
        }

        public static ProposalResult failed() {
            return new ProposalResult(List.of(), Diagnostic.FAILED);
        }
    }
}
