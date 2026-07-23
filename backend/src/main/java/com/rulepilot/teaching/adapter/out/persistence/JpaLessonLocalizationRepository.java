package com.rulepilot.teaching.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.application.LessonLocalizationRepository;
import com.rulepilot.teaching.domain.LessonLocalization;
import com.rulepilot.teaching.domain.LessonLocalization.SectionTranslation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaLessonLocalizationRepository implements LessonLocalizationRepository {

    private final ObjectMapper json = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public LessonLocalization save(LessonLocalization localization) {
        var existing = entityManager
                .createQuery(
                        "select value from LessonLocalizationEntity value where value.lessonId = :lessonId and value.locale = :locale",
                        LessonLocalizationEntity.class)
                .setParameter("lessonId", localization.lessonId())
                .setParameter("locale", localization.language().name())
                .getResultStream()
                .findFirst();
        if (existing.isPresent()) {
            existing.get().update(localization, json);
        } else {
            entityManager.persist(new LessonLocalizationEntity(localization, json));
        }
        entityManager.flush();
        return localization;
    }

    @Override
    public Optional<LessonLocalization> find(UUID lessonId, PlayerLocale language) {
        return entityManager
                .createQuery(
                        "select value from LessonLocalizationEntity value where value.lessonId = :lessonId and value.locale = :locale",
                        LessonLocalizationEntity.class)
                .setParameter("lessonId", lessonId)
                .setParameter("locale", language.name())
                .getResultStream()
                .findFirst()
                .map(value -> value.toDomain(json));
    }
}

@Entity(name = "LessonLocalizationEntity")
@Table(name = "lesson_localization")
class LessonLocalizationEntity {

    @Id UUID id;
    @Column(name = "lesson_id", nullable = false) UUID lessonId;
    @Column(nullable = false) String locale;
    @Column(nullable = false) String status;
    @Column(name = "translated_sections", columnDefinition = "text") String translatedSections;
    @Column(name = "failure_code") String failureCode;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected LessonLocalizationEntity() {}

    LessonLocalizationEntity(LessonLocalization localization, ObjectMapper json) {
        id = UUID.randomUUID();
        lessonId = localization.lessonId();
        locale = localization.language().name();
        createdAt = localization.createdAt();
        update(localization, json);
    }

    void update(LessonLocalization localization, ObjectMapper json) {
        if (!lessonId.equals(localization.lessonId()) || !locale.equals(localization.language().name())) {
            throw new IllegalArgumentException("lesson localization identity cannot change");
        }
        status = localization.status().name();
        translatedSections = write(localization.sections(), json);
        failureCode = localization.failureCode();
        updatedAt = localization.updatedAt();
    }

    LessonLocalization toDomain(ObjectMapper json) {
        return new LessonLocalization(
                lessonId,
                PlayerLocale.valueOf(locale),
                LessonLocalization.Status.valueOf(status),
                read(translatedSections, json),
                failureCode,
                createdAt,
                updatedAt);
    }

    private static String write(List<SectionTranslation> sections, ObjectMapper json) {
        if (sections.isEmpty()) return "[]";
        try {
            return json.writeValueAsString(sections);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("lesson localization cannot be serialized", exception);
        }
    }

    private static List<SectionTranslation> read(String value, ObjectMapper json) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return List.of(json.readValue(value, SectionTranslation[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored lesson localization is invalid", exception);
        }
    }
}
