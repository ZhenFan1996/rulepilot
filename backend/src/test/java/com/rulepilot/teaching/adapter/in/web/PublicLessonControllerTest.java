package com.rulepilot.teaching.adapter.in.web;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail;
import com.rulepilot.teaching.application.LessonLocalizationService;
import com.rulepilot.teaching.application.PublicCoverThumbnailService;
import com.rulepilot.teaching.application.PublicLessonCatalog;
import com.rulepilot.teaching.application.PublicLessonQuestionService;
import com.rulepilot.teaching.application.PublicLessonReader;
import com.rulepilot.teaching.application.RulebookIconGlossaryService;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicLessonControllerTest {

    private final PublicLessonReader lessons = mock(PublicLessonReader.class);
    private final PublicLessonCatalog catalog = mock(PublicLessonCatalog.class);
    private final PublicLessonQuestionService questions = mock(PublicLessonQuestionService.class);
    private final LessonLocalizationService localizations = mock(LessonLocalizationService.class);
    private final PublicCoverThumbnailService coverThumbnails = mock(PublicCoverThumbnailService.class);
    private final DocumentPageImages pageImages = mock(DocumentPageImages.class);
    private final DocumentPageImageCropper crops = mock(DocumentPageImageCropper.class);
    private final RulebookIconGlossaryService iconGlossary = mock(RulebookIconGlossaryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicLessonController(
                lessons, catalog, questions, localizations, coverThumbnails, pageImages, crops, iconGlossary)).build();
    }

    @Test
    void serves_a_cached_rulebook_front_when_no_external_game_cover_was_recorded() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        var lesson = lesson(planId, documentVersionId);
        var firstPage = new DocumentPageImages.PageImage(1, "image/png", new byte[] {1}, 100, 140);
        var thumbnail = new Thumbnail(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        when(lessons.find(planId)).thenReturn(Optional.of(lesson));
        when(pageImages.read(documentVersionId, Set.of(1))).thenReturn(List.of(firstPage));
        when(coverThumbnails.thumbnailForRulebookCover(documentVersionId, firstPage)).thenReturn(thumbnail);

        mockMvc.perform(get("/api/public/lessons/{planId}/cover", planId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=2592000")))
                .andExpect(content().bytes(thumbnail.content()));

        verify(coverThumbnails).thumbnailForRulebookCover(documentVersionId, firstPage);
    }

    @Test
    void serves_a_cited_full_page_as_a_browser_safe_jpeg() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        var lesson = lesson(planId, documentVersionId);
        var stored = new DocumentPageImages.PageImage(4, "image/png", new byte[] {1}, 800, 1_200);
        byte[] normalized = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        when(lessons.requireCitedPage(planId, 4)).thenReturn(lesson);
        when(pageImages.read(documentVersionId, Set.of(4))).thenReturn(List.of(stored));
        when(crops.crop(stored, 0, 0, 1_000, 1_000, 0)).thenReturn(normalized);

        mockMvc.perform(get("/api/public/lessons/{planId}/pages/{pageNumber}/image", planId, 4))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(normalized));

        verify(crops).crop(stored, 0, 0, 1_000, 1_000, 0);
    }

    @Test
    void serves_a_lightweight_cited_page_preview_for_inline_location() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        var lesson = lesson(planId, documentVersionId);
        var stored = new DocumentPageImages.PageImage(4, "image/png", new byte[] {1}, 800, 1_200);
        byte[] preview = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        when(lessons.requireCitedPage(planId, 4)).thenReturn(lesson);
        when(pageImages.read(documentVersionId, Set.of(4))).thenReturn(List.of(stored));
        when(crops.preview(stored)).thenReturn(preview);

        mockMvc.perform(get("/api/public/lessons/{planId}/pages/{pageNumber}/image/preview", planId, 4))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(preview));

        verify(crops).preview(stored);
    }

    @Test
    void accepts_a_bounded_public_learning_intent() throws Exception {
        UUID planId = UUID.randomUUID();
        when(questions.answer(eq(planId), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/public/lessons/{planId}/answers", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Explain the milestone.",
                                  "previousQuestion": "When does the milestone score?",
                                  "language": "en",
                                  "learningIntent": "DEFINE"
                                }
                                """))
                .andExpect(status().isNotFound());

        var request = ArgumentCaptor.forClass(PublicLessonQuestionService.QuestionRequest.class);
        verify(questions).answer(eq(planId), request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().learningIntent())
                .isEqualTo(com.rulepilot.assistant.RuleAnswering.PublicLearningIntent.DEFINE);
    }

    private PublicLessonReader.PublicLesson lesson(UUID planId, UUID documentVersionId) {
        var source = new IllustratedLesson(
                UUID.randomUUID(),
                planId,
                IllustratedLesson.LessonStatus.COMPLETE,
                List.of(),
                "cover-test",
                Instant.now());
        return new PublicLessonReader.PublicLesson(
                planId,
                documentVersionId,
                "Orbit Rules",
                "https://publisher.example/orbit-rules.pdf",
                null,
                null,
                source);
    }
}
