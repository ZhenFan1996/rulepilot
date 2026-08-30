package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads rendered rulebook pages before lesson planning. Its output is a page-scoped retrieval aid, never
 * player-facing lesson prose or an uncited rule answer. The application owns provider- and storage-aware batching;
 * this contract must not reject a complete result merely because a caller used a different batch size.
 */
public interface VisualRulebookPageCatalogModel {

    CatalogDraft summarize(CatalogRequest request);

    /**
     * Reads only the page-scoped rule facts needed to start an evidence-bound lesson. Implementations should avoid
     * whole-document audits here. The default keeps non-vision test adapters source-compatible.
     */
    default CatalogDraft summarizeForTeaching(CatalogRequest request) {
        return summarize(request);
    }

    /**
     * Returns a complete replacement after the same page Agent observes the complete rejected candidate, the exact
     * validation error, the original output contract, and every allowed page identity. The application decides
     * whether that observation is new; this port never owns an arbitrary correction-count limit.
     */
    default CatalogDraft correctTeachingCatalog(
            CatalogRequest request, TeachingCatalogRejection rejection) {
        throw new UnsupportedOperationException("visual Teaching catalog correction is unavailable");
    }

    enum TeachingCatalogRepairCode {
        MALFORMED_JSON,
        SCHEMA_MISMATCH,
        DUPLICATE_RULE_GROUP,
        PAGE_BINDING_MISMATCH
    }

    record TeachingCatalogRejection(
            String candidateJson,
            TeachingCatalogRepairCode code,
            String path,
            String reason,
            String schema,
            Set<Integer> allowedPageIds) {

        public TeachingCatalogRejection {
            if (candidateJson == null
                    || code == null
                    || path == null || path.isBlank()
                    || reason == null || reason.isBlank()
                    || schema == null || schema.isBlank()
                    || allowedPageIds == null
                    || allowedPageIds.isEmpty()
                    || allowedPageIds.stream().anyMatch(page -> page == null || page < 1)) {
                throw new IllegalArgumentException("visual Teaching catalog rejection is incomplete");
            }
            path = path.strip();
            reason = reason.strip();
            schema = schema.strip();
            allowedPageIds = Set.copyOf(allowedPageIds);
        }
    }

    final class TeachingCatalogContractViolation extends IllegalArgumentException {

        private final TeachingCatalogRepairCode repairCode;
        private final TeachingCatalogRejection rejection;

        public TeachingCatalogContractViolation(TeachingCatalogRepairCode repairCode) {
            this(repairCode, null, null);
        }

        public TeachingCatalogContractViolation(TeachingCatalogRepairCode repairCode, Throwable cause) {
            this(repairCode, null, cause);
        }

        public TeachingCatalogContractViolation(
                TeachingCatalogRepairCode repairCode,
                TeachingCatalogRejection rejection,
                Throwable cause) {
            super(violationMessage(repairCode, cause), cause);
            this.repairCode = repairCode;
            this.rejection = rejection;
        }

        public TeachingCatalogRepairCode repairCode() {
            return repairCode;
        }

        public Optional<TeachingCatalogRejection> rejection() {
            return Optional.ofNullable(rejection);
        }

        private static TeachingCatalogRepairCode requireRepairCode(TeachingCatalogRepairCode repairCode) {
            if (repairCode == null) throw new IllegalArgumentException("Teaching catalog repair code is required");
            return repairCode;
        }

        private static String violationMessage(TeachingCatalogRepairCode repairCode, Throwable cause) {
            TeachingCatalogRepairCode required = requireRepairCode(repairCode);
            return cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                    ? "visual Teaching catalog violated " + required
                    : cause.getMessage();
        }
    }

    default Optional<ModelExecutionIdentity> teachingStartupExecutionIdentity(String modelConfigurationOwner) {
        return Optional.empty();
    }

    default boolean available(String modelConfigurationOwner) {
        return true;
    }

    static VisualRulebookPageCatalogModel unavailable() {
        return new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new IllegalStateException("visual page catalog is unavailable");
            }

            @Override
            public boolean available(String modelConfigurationOwner) {
                return false;
            }
        };
    }

    record CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner, String rulebookTitle) {

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner) {
            this(pages, modelConfigurationOwner, null);
        }

        public CatalogRequest {
            if (pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException("visual page catalog request is invalid");
            }
            pages = List.copyOf(pages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
            rulebookTitle = rulebookTitle == null || rulebookTitle.isBlank() ? null : rulebookTitle.strip();
        }
    }

    record CatalogDraft(List<PageSummary> pages) {
        public CatalogDraft {
            if (pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException("visual page catalog draft is invalid");
            }
            pages = List.copyOf(pages);
        }
    }

    record ModelExecutionIdentity(String provider, String model) {
        public ModelExecutionIdentity {
            provider = auditValue(provider, "provider");
            model = auditValue(model, "model");
        }

        public String auditLabel() {
            return provider + "/" + model;
        }

        private static String auditValue(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("visual model " + label + " is required");
            }
            return value.strip();
        }
    }

    /** One page-owned rule relation returned as JSON. Player-facing prose is never parsed to rebuild this binding. */
    record RuleGroupFact(String identifier, String label, String fact) {
        public RuleGroupFact {
            if (identifier == null || identifier.isBlank()
                    || label == null || label.isBlank()
                    || fact == null || fact.isBlank()) {
                throw new IllegalArgumentException("visual rule-group fact is invalid");
            }
            identifier = identifier.strip();
            label = label.strip();
            fact = fact.strip();
        }
    }

    record PageSummary(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            List<VisualAnchor> visualAnchors,
            List<RuleGroupFact> ruleGroupFacts) {

        public PageSummary(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    List.of(),
                    List.of());
        }

        public PageSummary(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    List.of());
        }

        public PageSummary {
            if (pageNumber < 1
                    || (keywords != null
                            && keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank()))
                    || (visualAnchors != null && visualAnchors.stream().anyMatch(java.util.Objects::isNull))
                    || ruleGroupFacts == null
                    || ruleGroupFacts.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("visual page summary is invalid");
            }
            printedTerms = printedTerms == null || printedTerms.isBlank()
                    ? "No legible printed term on this page."
                    : printedTerms.strip();
            factualSummary = factualSummary == null || factualSummary.isBlank()
                    ? "该页没有可可靠转写的规则文字；请直接查看页面图像。"
                    : factualSummary.strip();
            keywords = keywords == null
                    ? List.of()
                    : keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors == null ? List.of() : visualAnchors.stream().distinct().toList();
            ruleGroupFacts = ruleGroupFacts.stream().distinct().toList();
        }
    }
}
