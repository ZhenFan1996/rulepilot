package com.rulepilot.teaching.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.RuleFactStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JpaVisualRulebookPageFacts implements VisualRulebookPageFacts, VisualRulebookPageFactSearch {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> RULE_GROUP_IDENTIFIERS = new TypeReference<>() {};
    private static final int MAX_CJK_FRAGMENTS = 16;
    private static final Set<String> SEARCH_FILLER = Set.of(
            "and", "are", "can", "does", "for", "from", "how", "into", "must", "not", "the", "this", "what",
            "when", "where", "with", "work", "works", "you", "your");
    private static final Set<String> GENERIC_RULE_TERMS = Set.of(
            "clause", "direct", "game", "page", "rule");
    private static final Set<String> CJK_QUESTION_FILLER = Set.of(
            "哪些", "什么", "如何", "怎么", "是否", "为何", "为什么");
    private static final Pattern CJK_RUN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern SHORT_PRINTED_IDENTIFIER = Pattern.compile("(?i)([a-z]{1,4})[#_-](\\d{1,4})");

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void replace(UUID documentVersionId, List<PageFact> pages) {
        if (documentVersionId == null || pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("visual page facts are required");
        }
        entityManager.createQuery("delete from VisualRulebookPageFactEntity p where p.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        pages.forEach(page -> entityManager.persist(new VisualRulebookPageFactEntity(documentVersionId, page)));
    }

    @Override
    @Transactional
    public void merge(UUID documentVersionId, List<PageFact> pages) {
        if (documentVersionId == null || pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("visual page facts are required");
        }
        Set<Integer> pageNumbers = pages.stream().map(PageFact::pageNumber).collect(java.util.stream.Collectors.toSet());
        entityManager.createQuery(
                        "delete from VisualRulebookPageFactEntity p where p.documentVersionId = :versionId "
                                + "and p.pageNumber in :pageNumbers")
                .setParameter("versionId", documentVersionId)
                .setParameter("pageNumbers", pageNumbers)
                .executeUpdate();
        pages.forEach(page -> entityManager.persist(new VisualRulebookPageFactEntity(documentVersionId, page)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()) return List.of();
        return entityManager.createQuery(
                        "select p from VisualRulebookPageFactEntity p "
                                + "where p.documentVersionId = :versionId and p.pageNumber in :pageNumbers "
                                + "order by p.pageNumber",
                        VisualRulebookPageFactEntity.class)
                .setParameter("versionId", documentVersionId)
                .setParameter("pageNumbers", pageNumbers)
                .getResultList()
                .stream()
                .map(VisualRulebookPageFactEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageFactMatch> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
        return find(documentVersionId, pageNumbers).stream()
                .map(fact -> new PageFactMatch(
                        fact.pageNumber(),
                        fact.printedTerms(),
                        fact.factualSummary(),
                        fact.keywords(),
                        1.0,
                        ruleFactStatus(
                                fact.ruleGroupIdentifiers(),
                                fact.ruleGroupInventoryComplete(),
                                fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<PageFactMatch> search(UUID documentVersionId, String query, int limit) {
        if (documentVersionId == null || query == null || query.isBlank() || query.length() > 600
                || limit < 1 || limit > 5) {
            throw new IllegalArgumentException("visual page fact search is invalid");
        }
        List<String> lexemes = searchLexemes(query);
        List<String> cjkFragments = cjkFragments(query);
        List<String> printedIdentifiers = printedIdentifiers(query);
        if (lexemes.isEmpty() && cjkFragments.isEmpty() && printedIdentifiers.isEmpty()) return List.of();
        String documentVector = "to_tsvector('simple', printed_terms || ' ' || factual_summary || ' ' || keywords)";
        String documentText = "lower(printed_terms || ' ' || factual_summary || ' ' || keywords)";
        String compactDocumentText = "regexp_replace(" + documentText + ", '\\s+', '', 'g')";
        Stream<String> identifierCoverage = IntStream.range(0, printedIdentifiers.size())
                .mapToObj(index -> "CASE WHEN position(:identifier" + index + " in " + compactDocumentText
                        + ") > 0 THEN 100 ELSE 0 END");
        Stream<String> lexicalCoverage = IntStream.range(0, lexemes.size())
                .mapToObj(index -> "CASE WHEN " + documentVector
                        + " @@ to_tsquery('simple', :term" + index + ") THEN "
                        + termWeight(lexemes.get(index)) + " ELSE 0 END");
        Stream<String> cjkCoverage = IntStream.range(0, cjkFragments.size())
                .mapToObj(index -> "CASE WHEN position(:cjk" + index + " in " + documentText
                        + ") > 0 THEN 1 ELSE 0 END");
        String coverage = Stream.concat(identifierCoverage, Stream.concat(lexicalCoverage, cjkCoverage))
                .collect(java.util.stream.Collectors.joining(" + "));
        String requested = lexemes.isEmpty()
                ? ""
                : "WITH requested AS (SELECT to_tsquery('simple', :query) AS terms)";
        String source = lexemes.isEmpty()
                ? "visual_rulebook_page_fact"
                : "visual_rulebook_page_fact, requested";
        String relevance = lexemes.isEmpty()
                ? "0.0"
                : "ts_rank_cd(" + documentVector + ", requested.terms)";
        String sql = """
                %s
                SELECT page_number, printed_terms, factual_summary, keywords,
                       (%s) AS matched_terms,
                       %s AS relevance,
                       rule_group_identifiers,
                       rule_group_inventory_complete
                FROM %s
                WHERE document_version_id = :versionId
                  AND schema_version = :schemaVersion
                  AND (%s) > 0
                ORDER BY matched_terms DESC, relevance DESC, page_number
                LIMIT :limit
                """.formatted(requested, coverage, relevance, source, coverage);
        var databaseQuery = entityManager.createNativeQuery(sql)
                .setParameter("versionId", documentVersionId)
                .setParameter("schemaVersion", PageFact.CURRENT_SCHEMA_VERSION)
                .setParameter("limit", limit);
        if (!lexemes.isEmpty()) {
            databaseQuery.setParameter("query", searchTerms(query));
        }
        IntStream.range(0, lexemes.size())
                .forEach(index -> databaseQuery.setParameter("term" + index, lexemes.get(index) + ":*"));
        IntStream.range(0, cjkFragments.size())
                .forEach(index -> databaseQuery.setParameter("cjk" + index, cjkFragments.get(index)));
        IntStream.range(0, printedIdentifiers.size())
                .forEach(index -> databaseQuery.setParameter("identifier" + index, printedIdentifiers.get(index)));
        List<Object[]> rows = databaseQuery.getResultList();
        return rows.stream()
                .map(row -> new PageFactMatch(
                        ((Number) row[0]).intValue(),
                        (String) row[1],
                        (String) row[2],
                        ((String) row[3]).lines().filter(value -> !value.isBlank()).toList(),
                        ((Number) row[4]).doubleValue() * 10 + ((Number) row[5]).doubleValue(),
                        ruleFactStatus(
                                deserializeRuleGroupIdentifiers((String) row[6]),
                                (Boolean) row[7],
                                true)))
                .toList();
    }

    private static RuleFactStatus ruleFactStatus(
            List<String> ruleGroupIdentifiers, boolean inventoryComplete, boolean currentSchema) {
        if (!currentSchema || !inventoryComplete) return RuleFactStatus.FACTS_INCOMPLETE;
        return ruleGroupIdentifiers.isEmpty()
                ? RuleFactStatus.NO_RULE_CONTENT
                : RuleFactStatus.CURRENT_RULE_FACTS;
    }

    private static List<String> deserializeRuleGroupIdentifiers(String serialized) {
        if (serialized == null || serialized.isBlank()) return List.of();
        try {
            return JSON.readValue(serialized, RULE_GROUP_IDENTIFIERS);
        } catch (JsonProcessingException invalidStoredData) {
            throw new IllegalStateException("stored visual rule-group identifiers are invalid", invalidStoredData);
        }
    }

    static String searchTerms(String query) {
        return searchLexemes(query).stream()
                .map(term -> term + ":*")
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    static List<String> printedIdentifiers(String query) {
        java.util.LinkedHashSet<String> identifiers = new java.util.LinkedHashSet<>();
        Matcher matcher = SHORT_PRINTED_IDENTIFIER.matcher(query);
        while (matcher.find() && identifiers.size() < 12) {
            identifiers.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(identifiers);
    }

    private static List<String> searchLexemes(String query) {
        java.util.LinkedHashSet<String> terms = Stream.of(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(JpaVisualRulebookPageFacts::isLatin)
                .filter(term -> term.length() >= 3)
                .filter(term -> !SEARCH_FILLER.contains(term))
                .map(JpaVisualRulebookPageFacts::normalizeEnglishTerm)
                .filter(term -> !GENERIC_RULE_TERMS.contains(term))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Matcher identifier = SHORT_PRINTED_IDENTIFIER.matcher(query);
        while (identifier.find() && terms.size() < 12) {
            terms.add(identifier.group(1).toLowerCase(Locale.ROOT));
            if (terms.size() < 12) terms.add(identifier.group(2));
        }
        return terms.stream().limit(12).toList();
    }

    static List<String> cjkFragments(String query) {
        java.util.LinkedHashSet<String> fragments = new java.util.LinkedHashSet<>();
        Matcher matcher = CJK_RUN.matcher(query);
        while (matcher.find()) {
            String run = matcher.group();
            if (run.length() == 1) {
                fragments.add(run);
                continue;
            }
            for (int index = 0; index < run.length() - 1; index++) {
                String fragment = run.substring(index, index + 2);
                if (!CJK_QUESTION_FILLER.contains(fragment)) fragments.add(fragment);
            }
        }
        if (fragments.size() <= MAX_CJK_FRAGMENTS) return List.copyOf(fragments);

        List<String> candidates = List.copyOf(fragments);
        java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
        for (int slot = 0; slot < MAX_CJK_FRAGMENTS; slot++) {
            int index = (int) Math.round((double) slot * (candidates.size() - 1) / (MAX_CJK_FRAGMENTS - 1));
            selected.add(candidates.get(index));
        }
        candidates.stream()
                .filter(fragment -> selected.size() < MAX_CJK_FRAGMENTS)
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private static boolean isLatin(String value) {
        return value.codePoints().allMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                        || Character.isDigit(codePoint));
    }

    private static String normalizeEnglishTerm(String term) {
        if (term.equals("placing") || term.equals("placed")) return "place";
        if (term.length() > 4 && term.endsWith("s") && !term.endsWith("ss")) {
            return term.substring(0, term.length() - 1);
        }
        return term;
    }

    private static int termWeight(String term) {
        return GENERIC_RULE_TERMS.contains(term) ? 1 : 3;
    }
}

@Entity(name = "VisualRulebookPageFactEntity")
@Table(name = "visual_rulebook_page_fact")
class VisualRulebookPageFactEntity {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<VisualAnchor>> VISUAL_ANCHORS = new TypeReference<>() {};
    private static final TypeReference<List<IconOccurrence>> ICON_OCCURRENCES = new TypeReference<>() {};
    private static final TypeReference<List<SourceDependency>> SOURCE_DEPENDENCIES = new TypeReference<>() {};
    private static final TypeReference<List<String>> RULE_GROUP_IDENTIFIERS = new TypeReference<>() {};

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "page_number", nullable = false)
    int pageNumber;

    @Column(name = "printed_terms", nullable = false, columnDefinition = "text")
    String printedTerms;

    @Column(name = "factual_summary", nullable = false, columnDefinition = "text")
    String factualSummary;

    @Column(nullable = false, columnDefinition = "text")
    String keywords;

    @Column(name = "visual_anchors", nullable = false, columnDefinition = "text")
    String visualAnchors;

    @Column(name = "icon_occurrences", nullable = false, columnDefinition = "text")
    String iconOccurrences;

    @Column(name = "icon_inventory_complete", nullable = false)
    boolean iconInventoryComplete;

    @Column(name = "schema_version", nullable = false)
    int schemaVersion;

    @Column(name = "source_dependencies", nullable = false, columnDefinition = "text")
    String sourceDependencies;

    @Column(name = "rule_group_identifiers", nullable = false, columnDefinition = "text")
    String ruleGroupIdentifiers;

    @Column(name = "rule_group_inventory_complete", nullable = false)
    boolean ruleGroupInventoryComplete;

    protected VisualRulebookPageFactEntity() {}

    VisualRulebookPageFactEntity(UUID documentVersionId, PageFact page) {
        this.id = UUID.randomUUID();
        this.documentVersionId = documentVersionId;
        this.pageNumber = page.pageNumber();
        this.printedTerms = page.printedTerms();
        this.factualSummary = page.factualSummary();
        this.keywords = String.join("\n", page.keywords());
        this.visualAnchors = serialize(page.visualAnchors());
        this.iconOccurrences = serialize(page.iconOccurrences());
        this.iconInventoryComplete = page.iconInventoryComplete();
        this.schemaVersion = page.schemaVersion();
        this.sourceDependencies = serialize(page.sourceDependencies());
        this.ruleGroupIdentifiers = serialize(page.ruleGroupIdentifiers());
        this.ruleGroupInventoryComplete = page.ruleGroupInventoryComplete();
    }

    PageFact toDomain() {
        return new PageFact(
                pageNumber,
                printedTerms,
                factualSummary,
                keywords.lines().filter(value -> !value.isBlank()).toList(),
                deserialize(visualAnchors),
                deserializeIcons(iconOccurrences),
                iconInventoryComplete,
                schemaVersion,
                deserializeSourceDependencies(sourceDependencies),
                deserializeRuleGroupIdentifiers(ruleGroupIdentifiers),
                ruleGroupInventoryComplete);
    }

    private static String serialize(List<?> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("could not serialize visual anchors", failure);
        }
    }

    private static List<VisualAnchor> deserialize(String serialized) {
        if (serialized == null || serialized.isBlank()) return List.of();
        try {
            return JSON.readValue(serialized, VISUAL_ANCHORS);
        } catch (JsonProcessingException invalidStoredData) {
            throw new IllegalStateException("stored visual anchors are invalid", invalidStoredData);
        }
    }

    private static List<IconOccurrence> deserializeIcons(String serialized) {
        if (serialized == null || serialized.isBlank()) return List.of();
        try {
            return JSON.readValue(serialized, ICON_OCCURRENCES);
        } catch (JsonProcessingException invalidStoredData) {
            throw new IllegalStateException("stored visual icon occurrences are invalid", invalidStoredData);
        }
    }

    private static List<SourceDependency> deserializeSourceDependencies(String serialized) {
        if (serialized == null || serialized.isBlank()) return List.of();
        try {
            return JSON.readValue(serialized, SOURCE_DEPENDENCIES);
        } catch (JsonProcessingException invalidStoredData) {
            throw new IllegalStateException("stored visual source dependencies are invalid", invalidStoredData);
        }
    }

    private static List<String> deserializeRuleGroupIdentifiers(String serialized) {
        if (serialized == null || serialized.isBlank()) return List.of();
        try {
            return JSON.readValue(serialized, RULE_GROUP_IDENTIFIERS);
        } catch (JsonProcessingException invalidStoredData) {
            throw new IllegalStateException("stored visual rule-group identifiers are invalid", invalidStoredData);
        }
    }
}
