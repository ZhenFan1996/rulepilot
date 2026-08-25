package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.application.BudgetedAgentInvocations;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiVisualRegionLocatorTest {

    @Test
    void reportsTheOwnersConfiguredVisualCapability() {
        var models = mock(RuntimeModelConfiguration.class);
        when(models.usesFake(Role.VISUAL, "text-player")).thenReturn(true);
        when(models.usesFake(Role.VISUAL, "visual-player")).thenReturn(false);
        when(models.supportsVision(Role.VISUAL, "visual-player")).thenReturn(true);
        var locator = new SpringAiVisualRegionLocator(models);

        assertThat(locator.supportsVisualEvidence("text-player")).isFalse();
        assertThat(locator.supportsVisualEvidence("visual-player")).isTrue();
    }

    @Test
    void parsesOnlyTheCandidateIdProtocolAndRejectsAnyModelAuthoredGeometry() {
        var guide = VisualLocatorResponsePolicy.parseModelGuide("""
                {"batchAction":"STOP","reviews":[{"stepPosition":2,"action":"ACCEPT_CANDIDATE","candidateId":"opaque_7",
                "label":"行动状态","visibleDescription":"棋子位于弧形轨道上",
                "supportedClaimRefs":["C1"]}]}
                """).orElseThrow();

        assertThat(guide.reviews()).singleElement().satisfies(review -> {
            assertThat(review.candidateId()).isEqualTo("opaque_7");
            assertThat(review.supportedClaimRefs()).containsExactly("C1");
        });
        assertThat(guide.batchAction()).isEqualTo(VisualRegionLocator.BatchAction.STOP);
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("""
                {"batchAction":"STOP","reviews":[{"stepPosition":2,"action":"ACCEPT_CANDIDATE","candidateId":"opaque_7",
                "label":"行动状态","visibleDescription":"棋子位于弧形轨道上",
                "supportedClaimRefs":["C1"],"x":100}]}
                """)).isEmpty();
    }

    @Test
    void mapsAnAcceptedOpaqueIdBackToApplicationOwnedGeometryAndSourceKind() throws IOException {
        UUID evidence = UUID.randomUUID();
        Candidate candidate = candidate("opaque_7", 4, new Rectangle(120, 180, 360, 240));
        Runtime runtime = runtime("""
                {"batchAction":"STOP","reviews":[{"stepPosition":2,"action":"ACCEPT_CANDIDATE","candidateId":"opaque_7",
                "label":"行动状态","visibleDescription":"棋子位于弧形轨道上",
                "supportedClaimRefs":["C1"]}]}
                """);

        var result = runtime.locator().locateGuideWithResult(request(
                List.of(new VisualRegionLocator.Claim(evidence, "沿轨道移动。", List.of(4), 2)),
                List.of(candidate),
                List.of(page(4, solidPng(Color.ORANGE))),
                1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.pageNumber()).isEqualTo(4);
            assertThat(List.of(region.x(), region.y(), region.width(), region.height()))
                    .containsExactly(120, 180, 360, 240);
            assertThat(region.sourceKind()).isEqualTo(VisualSourceKind.PAGE_REGION);
            assertThat(region.supportedEvidenceIds()).containsExactly(evidence);
            assertThat(region.supportedStepPositions()).containsExactly(2);
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(runtime.model()).call(prompt.capture());
        String userText = prompt.getValue().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(message -> message.getText())
                .findFirst()
                .orElseThrow();
        assertThat(userText)
                .contains(
                        "\"candidateId\":\"opaque_7\"",
                        "\"attachmentIndex\":1",
                        "\"pageNumber\":4",
                        "\"ref\":\"C1\"",
                        "\"sourcePages\":[4]")
                .doesNotContain("candidateId=", "attachmentIndex=", "sourcePages=");
    }

    @Test
    void rejectsUnknownOrRepeatedCandidateIdsWithoutSemanticRetry() throws IOException {
        Candidate candidate = candidate("known_1", 4, new Rectangle(100, 100, 300, 300));
        Runtime unknown = runtime("""
                {"batchAction":"STOP","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE","candidateId":"unknown_9",
                "label":"棋盘","visibleDescription":"中央棋盘区域","supportedClaimRefs":["C1"]}]}
                """);
        Runtime repeated = runtime("""
                {"batchAction":"STOP","reviews":[
                {"stepPosition":1,"action":"ACCEPT_CANDIDATE","candidateId":"known_1",
                "label":"棋盘","visibleDescription":"中央棋盘区域","supportedClaimRefs":["C1"]},
                {"stepPosition":1,"action":"ACCEPT_CANDIDATE","candidateId":"known_1",
                "label":"棋盘","visibleDescription":"同一中央棋盘区域","supportedClaimRefs":["C1"]}]}
                """);
        VisualLocationRequest request = request(
                List.of(claim(1, 4)), List.of(candidate), List.of(page(4, solidPng(Color.WHITE))), 2);

        assertThat(unknown.locator().locateGuideWithResult(request).diagnostic())
                .isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
        assertThat(repeated.locator().locateGuideWithResult(request).diagnostic())
                .isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
        verify(unknown.model(), times(1)).call(any(Prompt.class));
        verify(repeated.model(), times(1)).call(any(Prompt.class));
    }

    @Test
    void rejectsCrossPageAndCrossStepClaimBindingsAtomically() throws IOException {
        Candidate pageTwo = candidate("page_two", 2, new Rectangle(80, 80, 400, 300));
        List<VisualRegionLocator.Claim> claims = List.of(claim(1, 1), claim(2, 2));
        VisualLocationRequest request = request(
                claims, List.of(pageTwo), List.of(page(2, solidPng(Color.WHITE))), 1);
        Runtime crossPage = runtime("""
                {"batchAction":"STOP","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE","candidateId":"page_two",
                "label":"组件","visibleDescription":"一组组件","supportedClaimRefs":["C1"]}]}
                """);
        Runtime crossStep = runtime("""
                {"batchAction":"STOP","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE","candidateId":"page_two",
                "label":"组件","visibleDescription":"一组组件","supportedClaimRefs":["C2"]}]}
                """);

        assertThat(crossPage.locator().locateGuideWithResult(request).diagnostic())
                .isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
        assertThat(crossStep.locator().locateGuideWithResult(request).diagnostic())
                .isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
    }

    @Test
    void preservesTypedNoVisualForAProseOnlyCandidateWithoutRetrying() throws IOException {
        Runtime runtime = runtime("""
                {"batchAction":"STOP","reviews":[{"stepPosition":1,"action":"NO_VISUAL","candidateId":null,
                "label":null,"visibleDescription":null,"supportedClaimRefs":[]}]}
                """);
        VisualLocationRequest request = request(
                List.of(claim(1, 3)),
                List.of(candidate("candidate_1", 3, new Rectangle(0, 0, 550, 550))),
                List.of(page(3, solidPng(Color.WHITE))),
                1);

        assertThat(runtime.locator().locateGuideWithResult(request).diagnostic())
                .isEqualTo(Diagnostic.EXPLICIT_NO_REGION);
        verify(runtime.model(), times(1)).call(any(Prompt.class));
    }

    @Test
    void retriesMalformedStructureAtMostOnceButDoesNotChangeCandidateAttachments() throws IOException {
        Runtime runtime = runtime(
                "not-json",
                """
                {"batchAction":"STOP","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE","candidateId":"candidate_1",
                "label":"图例","visibleDescription":"三个图标由箭头连接","supportedClaimRefs":["C1"]}]}
                """);
        VisualLocationRequest request = request(
                List.of(claim(1, 3)),
                List.of(candidate("candidate_1", 3, new Rectangle(0, 0, 550, 550))),
                List.of(page(3, solidPng(Color.GREEN))),
                1);

        assertThat(runtime.locator().locateGuideWithResult(request).diagnostic()).isEqualTo(Diagnostic.FOUND);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(runtime.model(), times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().getLast().getInstructions().stream().map(message -> message.getText()))
                .anySatisfy(text -> assertThat(text).contains("batchAction plus six-field review contract"));
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> assertThat(prompt.getInstructions().stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getMedia())
                .hasSize(1));
    }

    @Test
    void stopsAfterOneMalformedRepairAttempt() throws IOException {
        Runtime runtime = runtime("not-json", "still-not-json");
        VisualLocationRequest request = request(
                List.of(claim(1, 3)),
                List.of(candidate("candidate_1", 3, new Rectangle(0, 0, 550, 550))),
                List.of(page(3, solidPng(Color.GREEN))),
                1);

        assertThat(runtime.locator().locateGuideWithResult(request).diagnostic())
                .isEqualTo(Diagnostic.MALFORMED_RESPONSE);
        verify(runtime.model(), times(2)).call(any(Prompt.class));
    }

    @Test
    void reservesAndRecordsEveryRealModelAttemptIncludingMalformedRepair() throws IOException {
        UUID runId = UUID.randomUUID();
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        when(execution.reserve(
                        eq(runId),
                        eq(AgentExecutionControl.ActivityType.MODEL),
                        anyString(),
                        anyInt()))
                .thenAnswer(call -> new AgentExecutionControl.InvocationReservation(
                        UUID.randomUUID(),
                        runId,
                        call.getArgument(1),
                        call.getArgument(2),
                        call.getArgument(3)));
        Runtime runtime = runtime(
                new BudgetedAgentInvocations(execution),
                "not-json",
                """
                {"batchAction":"STOP","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE",
                "candidateId":"candidate_1","label":"图例","visibleDescription":"三个图标由箭头连接",
                "supportedClaimRefs":["C1"]}]}
                """);
        VisualLocationRequest request = request(
                List.of(claim(1, 3)),
                List.of(candidate("candidate_1", 3, new Rectangle(0, 0, 550, 550))),
                List.of(page(3, solidPng(Color.GREEN))),
                1,
                false,
                runId);

        assertThat(runtime.locator().locateGuideWithResult(request).diagnostic()).isEqualTo(Diagnostic.FOUND);
        ArgumentCaptor<String> operations = ArgumentCaptor.forClass(String.class);
        verify(execution, times(2)).reserve(
                eq(runId),
                eq(AgentExecutionControl.ActivityType.MODEL),
                operations.capture(),
                anyInt());
        assertThat(operations.getAllValues()).containsExactly(
                "visualCandidateBatch|1|1",
                "visualCandidateBatch|1|2");
        verify(execution, times(2)).complete(
                any(AgentExecutionControl.InvocationReservation.class),
                eq(AgentExecutionControl.ActivityOutcome.SUCCEEDED),
                eq(1_000),
                anyLong(),
                anyString());
        verify(runtime.model(), times(2)).call(any(Prompt.class));
    }

    @Test
    void aRunBudgetStopBeforeTheNextBatchPreventsAnotherProviderCall() throws IOException {
        UUID runId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger reservations = new java.util.concurrent.atomic.AtomicInteger();
        AuditedAgentInvocations budget = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID requestedRunId,
                    AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                if (reservations.incrementAndGet() > 1) {
                    throw new AgentExecutionStoppedException(
                            AgentExecutionStoppedException.StopReason.MODEL_BUDGET);
                }
                return invocation.get();
            }
        };
        Runtime runtime = runtime(budget, """
                {"batchAction":"CONTINUE","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE",
                "candidateId":"candidate_1","label":"图例","visibleDescription":"三个图标由箭头连接",
                "supportedClaimRefs":["C1"]}]}
                """);
        Candidate candidate = candidate("candidate_1", 3, new Rectangle(0, 0, 550, 550));

        assertThat(runtime.locator().locateGuideWithResult(request(
                        List.of(claim(1, 3)),
                        List.of(candidate),
                        List.of(page(3, solidPng(Color.GREEN))),
                        1,
                        true,
                        runId)).batchAction())
                .isEqualTo(VisualRegionLocator.BatchAction.CONTINUE);
        assertThatThrownBy(() -> runtime.locator().locateGuideWithResult(request(
                        List.of(claim(1, 3)),
                        List.of(candidate),
                        List.of(page(3, solidPng(Color.GREEN))),
                        2,
                        false,
                        runId)))
                .isInstanceOf(AgentExecutionStoppedException.class);
        assertThat(reservations).hasValue(2);
        verify(runtime.model(), times(1)).call(any(Prompt.class));
    }

    @Test
    void sendsLocallyCroppedCandidateImagesInManifestOrder() throws IOException {
        Candidate right = candidate("right_crop", 1, new Rectangle(500, 0, 500, 1_000));
        Candidate left = candidate("left_crop", 1, new Rectangle(0, 0, 500, 1_000));
        VisualLocationRequest request = request(
                List.of(claim(1, 1)),
                List.of(right, left),
                List.of(page(1, splitPng())),
                1);
        var locator = new SpringAiVisualRegionLocator(mock(RuntimeModelConfiguration.class));

        var attachments = locator.candidateAttachments(request);

        assertThat(attachments)
                .extracting(
                        SpringAiVisualRegionLocator.CandidateAttachment::candidateId,
                        SpringAiVisualRegionLocator.CandidateAttachment::attachmentIndex)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("right_crop", 1),
                        org.assertj.core.groups.Tuple.tuple("left_crop", 2));
        assertThat(centerColor(attachments.getFirst().content()).getBlue()).isGreaterThan(200);
        assertThat(centerColor(attachments.getLast().content()).getRed()).isGreaterThan(200);
        assertThat(VisualLocatorResponsePolicy.candidateManifest(request.candidates()))
                .extracting(entry -> entry.get("candidateId"), entry -> entry.get("attachmentIndex"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("right_crop", 1),
                        org.assertj.core.groups.Tuple.tuple("left_crop", 2));
        String manifestJson = VisualLocatorResponsePolicy.promptJson(
                VisualLocatorResponsePolicy.candidateManifest(request.candidates()));
        assertThat(manifestJson)
                .doesNotContain("rectangle", "sourceKind", "\"x\"", "\"y\"", "width", "height");
    }

    @Test
    void releasesEachDecodedPageBeforeDecodingTheNextWithoutReorderingInterleavedCandidates() throws IOException {
        AtomicInteger activePages = new AtomicInteger();
        AtomicInteger peakActivePages = new AtomicInteger();
        List<Integer> decodedPages = new ArrayList<>();
        class TrackedPage extends BufferedImage {
            private boolean released;

            TrackedPage(Color color) {
                super(100, 100, BufferedImage.TYPE_INT_RGB);
                int active = activePages.incrementAndGet();
                peakActivePages.accumulateAndGet(active, Math::max);
                Graphics2D graphics = createGraphics();
                try {
                    graphics.setColor(color);
                    graphics.fillRect(0, 0, getWidth(), getHeight());
                } finally {
                    graphics.dispose();
                }
            }

            @Override
            public void flush() {
                if (!released) {
                    released = true;
                    activePages.decrementAndGet();
                }
                super.flush();
            }
        }
        var locator = new SpringAiVisualRegionLocator(mock(RuntimeModelConfiguration.class)) {
            @Override
            BufferedImage decode(VisualRegionLocator.PageImage page) {
                decodedPages.add(page.pageNumber());
                return new TrackedPage(page.pageNumber() == 1 ? Color.RED : Color.BLUE);
            }
        };
        VisualLocationRequest request = request(
                List.of(claim(1, 1), claim(2, 2)),
                List.of(
                        candidate("page_2_right", 2, new Rectangle(500, 0, 500, 1_000)),
                        candidate("page_1", 1, new Rectangle(0, 0, 550, 550)),
                        candidate("page_2_left", 2, new Rectangle(0, 0, 500, 1_000))),
                List.of(page(1, solidPng(Color.RED)), page(2, solidPng(Color.BLUE))),
                1);

        var attachments = locator.candidateAttachments(request);

        assertThat(decodedPages).containsExactly(1, 2);
        assertThat(peakActivePages).hasValue(1);
        assertThat(activePages).hasValue(0);
        assertThat(attachments)
                .extracting(
                        SpringAiVisualRegionLocator.CandidateAttachment::candidateId,
                        SpringAiVisualRegionLocator.CandidateAttachment::attachmentIndex)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("page_2_right", 1),
                        org.assertj.core.groups.Tuple.tuple("page_1", 2),
                        org.assertj.core.groups.Tuple.tuple("page_2_left", 3));
        assertThat(centerColor(attachments.get(0).content()).getBlue()).isGreaterThan(200);
        assertThat(centerColor(attachments.get(1).content()).getRed()).isGreaterThan(200);
        assertThat(centerColor(attachments.get(2).content()).getBlue()).isGreaterThan(200);
    }

    @Test
    void enforcesOnlyThePerCallAttachmentBatchInsteadOfAFinalPublicationCount() throws IOException {
        List<Candidate> candidates = List.of(
                candidate("candidate_1", 1, new Rectangle(0, 0, 550, 550)),
                candidate("candidate_2", 1, new Rectangle(450, 0, 550, 550)),
                candidate("candidate_3", 1, new Rectangle(0, 450, 550, 550)));

        VisualLocationRequest request = request(
                List.of(claim(1, 1)), candidates, List.of(page(1, solidPng(Color.WHITE))), 1);

        assertThat(request.candidates()).hasSize(3);
        assertThat(request.batchNumber()).isOne();
        assertThat(request.hasMoreCandidates()).isFalse();
        assertThat(VisualLocationRequest.MAX_CANDIDATES_PER_BATCH).isEqualTo(12);
        assertThatThrownBy(() -> request(
                        List.of(claim(1, 1)),
                        java.util.stream.IntStream.rangeClosed(1, 13)
                                .mapToObj(index -> candidate(
                                        "candidate_" + index,
                                        1,
                                        new Rectangle((index - 1) * 10, 0, 20, 20)))
                                .toList(),
                        List.of(page(1, solidPng(Color.WHITE))),
                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(
                        List.of(claim(1, 1)),
                        List.of(
                                candidate("same_geometry_1", 1, new Rectangle(0, 0, 550, 550)),
                                candidate("same_geometry_2", 1, new Rectangle(0, 0, 550, 550))),
                        List.of(page(1, solidPng(Color.WHITE))),
                        1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesContinueToStopOnTheLastBatchWithoutDiscardingValidReviews() throws IOException {
        String response = """
                {"batchAction":"CONTINUE","reviews":[{"stepPosition":1,"action":"ACCEPT_CANDIDATE",
                "candidateId":"candidate_1","label":"图例","visibleDescription":"图标由箭头连接",
                "supportedClaimRefs":["C1"]}]}
                """;
        Candidate candidate = candidate("candidate_1", 1, new Rectangle(0, 0, 550, 550));
        Runtime withNextBatch = runtime(response);
        Runtime withoutNextBatch = runtime(response);

        var continued = withNextBatch.locator().locateGuideWithResult(request(
                List.of(claim(1, 1)),
                List.of(candidate),
                List.of(page(1, solidPng(Color.WHITE))),
                1,
                true));
        var normalized = withoutNextBatch.locator().locateGuideWithResult(request(
                List.of(claim(1, 1)),
                List.of(candidate),
                List.of(page(1, solidPng(Color.WHITE))),
                1,
                false));

        assertThat(continued.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(continued.batchAction()).isEqualTo(VisualRegionLocator.BatchAction.CONTINUE);
        assertThat(normalized.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(normalized.batchAction()).isEqualTo(VisualRegionLocator.BatchAction.STOP);
        assertThat(normalized.regions()).singleElement();
    }

    @Test
    void requestsDeterministicQwenJsonModeWithABoundedResponse() {
        var options = SpringAiVisualRegionLocator.qwenJsonOptions("qwen3-vl-plus").build();

        assertThat(options.getModel()).isEqualTo("qwen3-vl-plus");
        assertThat(options.getTemperature()).isZero();
        assertThat(options.getMaxTokens()).isEqualTo(1_000);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(SpringAiVisualRegionLocator.QWEN_SYSTEM)
                .contains("ACCEPT_CANDIDATE", "NO_VISUAL", "candidateId", "STOP", "CONTINUE")
                .doesNotContain("visualBudget")
                .doesNotContain("pageNumber, label", " x,", "width", "height");
    }

    private Runtime runtime(String... responses) {
        return runtime(null, responses);
    }

    private Runtime runtime(AuditedAgentInvocations invocations, String... responses) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel model = mock(ChatModel.class);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder().model("visual-test").build();
        when(configuration.usesFake(Role.VISUAL, "owner")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "owner")).thenReturn(true);
        when(configuration.providerFor(Role.VISUAL, "owner")).thenReturn("qwen");
        when(configuration.modelNameFor(Role.VISUAL, "owner")).thenReturn("visual-test");
        when(configuration.modelFor(Role.VISUAL, "owner")).thenReturn(model);
        when(model.getDefaultOptions()).thenReturn(defaults);
        when(model.getOptions()).thenReturn(defaults);
        ChatResponse[] values = java.util.Arrays.stream(responses)
                .map(this::response)
                .toArray(ChatResponse[]::new);
        when(model.call(any(Prompt.class)))
                .thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
        return new Runtime(
                invocations == null
                        ? new SpringAiVisualRegionLocator(configuration)
                        : new SpringAiVisualRegionLocator(configuration, invocations),
                model);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private VisualLocationRequest request(
            List<VisualRegionLocator.Claim> claims,
            List<Candidate> candidates,
            List<VisualRegionLocator.PageImage> pages,
            int batchNumber) {
        return request(claims, candidates, pages, batchNumber, false);
    }

    private VisualLocationRequest request(
            List<VisualRegionLocator.Claim> claims,
            List<Candidate> candidates,
            List<VisualRegionLocator.PageImage> pages,
            int batchNumber,
            boolean hasMoreCandidates) {
        return request(claims, candidates, pages, batchNumber, hasMoreCandidates, null);
    }

    private VisualLocationRequest request(
            List<VisualRegionLocator.Claim> claims,
            List<Candidate> candidates,
            List<VisualRegionLocator.PageImage> pages,
            int batchNumber,
            boolean hasMoreCandidates,
            UUID runId) {
        return new VisualLocationRequest(
                "行动",
                claims,
                candidates,
                pages,
                "owner",
                runId == null ? null : UUID.randomUUID(),
                runId,
                batchNumber,
                hasMoreCandidates);
    }

    private Candidate candidate(String id, int page, Rectangle rectangle) {
        return new Candidate(id, page, rectangle, VisualSourceKind.PAGE_REGION);
    }

    private VisualRegionLocator.Claim claim(int step, int page) {
        return new VisualRegionLocator.Claim(UUID.randomUUID(), "核对这个可见区域。", List.of(page), step);
    }

    private VisualRegionLocator.PageImage page(int page, byte[] content) {
        return new VisualRegionLocator.PageImage(page, "image/png", content);
    }

    private byte[] solidPng(Color color) throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return png(image);
    }

    private byte[] splitPng() throws IOException {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 100, 100);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(100, 0, 100, 100);
        } finally {
            graphics.dispose();
        }
        return png(image);
    }

    private byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private Color centerColor(byte[] content) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        return new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
    }

    private record Runtime(SpringAiVisualRegionLocator locator, ChatModel model) {}

}
