package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a small batch of rendered rulebook pages before lesson planning. Its output is a page-scoped retrieval aid,
 * never player-facing lesson prose or an uncited rule answer.
 */
public interface VisualRulebookPageCatalogModel {

    /** A teaching-start request stays bounded so an unbounded PDF cannot exhaust model context or response budget. */
    int MAX_PAGES_PER_REQUEST = 8;

    CatalogDraft summarize(CatalogRequest request);

    /**
     * Reads only the page-scoped rule facts needed to start an evidence-bound lesson. Implementations should avoid
     * whole-document audits here. The default keeps non-vision test adapters source-compatible.
     */
    default CatalogDraft summarizeForTeaching(CatalogRequest request) {
        return summarize(request);
    }

    /**
     * Performs one changed, validator-owned repair request for a typed Teaching catalog violation. The repair code is
     * the complete input contract: raw model output and exception prose must never be copied into the next prompt.
     */
    default CatalogDraft repairTeachingCatalog(CatalogRequest request, TeachingCatalogRepairCode repairCode) {
        throw new UnsupportedOperationException("visual Teaching catalog repair is unavailable");
    }

    enum TeachingCatalogRepairCode {
        MALFORMED_JSON,
        SCHEMA_MISMATCH,
        DUPLICATE_RULE_GROUP,
        PAGE_BINDING_MISMATCH
    }

    final class TeachingCatalogContractViolation extends IllegalArgumentException {

        private final TeachingCatalogRepairCode repairCode;

        public TeachingCatalogContractViolation(TeachingCatalogRepairCode repairCode) {
            this(repairCode, null);
        }

        public TeachingCatalogContractViolation(TeachingCatalogRepairCode repairCode, Throwable cause) {
            super(violationMessage(repairCode, cause), cause);
            this.repairCode = repairCode;
        }

        public TeachingCatalogRepairCode repairCode() {
            return repairCode;
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
            if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES_PER_REQUEST) {
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
            if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES_PER_REQUEST) {
                throw new IllegalArgumentException("visual page catalog draft is invalid");
            }
            pages = List.copyOf(pages);
        }
    }

    /**
     * A compact page-role ledger used only to build an immutable source-bound plan. Coverage tags express teaching
     * obligations visible on a page; they are not themselves rule claims and never replace the page evidence.
     */
    record SourceDependency(String title, List<String> missingCoverageTags) {

        public SourceDependency {
            if (title == null || title.isBlank()
                    || missingCoverageTags == null
                    || missingCoverageTags.stream().anyMatch(tag -> tag == null || tag.isBlank())) {
                throw new IllegalArgumentException("visual teaching source dependency is invalid");
            }
            title = title.strip();
            missingCoverageTags = missingCoverageTags.stream().map(String::strip).distinct().toList();
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
            List<SourceDependency> sourceDependencies,
            List<String> ruleGroupIdentifiers,
            boolean ruleGroupInventoryComplete,
            List<VisualQuantityObservation> quantityObservations,
            List<RuleGroupFact> ruleGroupFacts) {

        public PageSummary(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
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
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of());
        }

        public PageSummary(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors,
                List<SourceDependency> sourceDependencies,
                List<String> ruleGroupIdentifiers,
                boolean ruleGroupInventoryComplete,
                List<VisualQuantityObservation> quantityObservations) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    sourceDependencies,
                    ruleGroupIdentifiers,
                    ruleGroupInventoryComplete,
                    quantityObservations,
                    List.of());
        }

        public PageSummary {
            if (pageNumber < 1
                    || (keywords != null
                            && keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank()))
                    || (visualAnchors != null && visualAnchors.stream().anyMatch(java.util.Objects::isNull))
                    || sourceDependencies == null
                    || sourceDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || ruleGroupIdentifiers == null
                    || ruleGroupIdentifiers.stream()
                            .anyMatch(identifier -> identifier == null || identifier.isBlank())
                    || quantityObservations == null
                    || quantityObservations.stream().anyMatch(java.util.Objects::isNull)
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
            keywords = keywords == null || keywords.isEmpty()
                    ? List.of("page " + pageNumber)
                    : keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors == null ? List.of() : visualAnchors.stream().distinct().toList();
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            ruleGroupIdentifiers = ruleGroupIdentifiers.stream().map(String::strip).distinct().toList();
            quantityObservations = List.copyOf(new java.util.LinkedHashSet<>(quantityObservations));
            ruleGroupFacts = ruleGroupFacts.stream().distinct().toList();
            Set<String> ruleGroupIdentities = Set.copyOf(ruleGroupIdentifiers);
            Set<String> factIdentities = ruleGroupFacts.stream()
                    .map(RuleGroupFact::identifier)
                    .collect(java.util.stream.Collectors.toSet());
            if (ruleGroupInventoryComplete && !factIdentities.equals(ruleGroupIdentities)) {
                throw new IllegalArgumentException("visual rule-group facts must exactly match their JSON identifiers");
            }
            if (quantityObservations.stream().anyMatch(observation -> observation.pageNumber() != pageNumber
                    || !ruleGroupIdentities.contains(observation.ruleGroupIdentifier()))) {
                throw new IllegalArgumentException(
                        "visual quantity observation must match its page and rule group");
            }
            VisualQuantityObservation.appendEvidence(factualSummary, quantityObservations);
        }
    }
}
