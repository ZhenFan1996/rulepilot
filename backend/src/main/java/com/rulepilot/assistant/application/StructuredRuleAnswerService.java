package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Persists one native Answer Agent run and publishes its validated terminal response unchanged. */
@Service
@Profile("!test")
public class StructuredRuleAnswerService implements RuleAnswering {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredRuleAnswerService.class);

    private final NativeRuleAnswerAgent answerAgent;
    private final AnswerRunLifecycle runLifecycle;
    private final ObservationRegistry observations;

    public StructuredRuleAnswerService(
            NativeRuleAnswerAgent answerAgent,
            AssistantRuns runs,
            ObservationRegistry observations) {
        this.answerAgent = answerAgent;
        this.runLifecycle = new AnswerRunLifecycle(runs);
        this.observations = observations;
    }

    public AnswerCreation answerWithRun(
            String question, QuestionContext context, String username, UUID gameSessionId) {
        return answerWithRun(question, context, username, gameSessionId, ignored -> {});
    }

    public AnswerCreation answerWithRun(
            String question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            Consumer<UUID> runStarted) {
        Consumer<UUID> listener = runStarted == null ? ignored -> {} : runStarted;
        return Observation.createNotStarted("rulepilot.answer.workflow", observations)
                .contextualName("answer-agent")
                .observe(() -> answerObserved(question, context, username, gameSessionId, listener));
    }

    @Override
    public AnswerResult answerForPublicReader(
            UUID documentVersionId, String question, String previousQuestion) {
        return answerForPublicReader(documentVersionId, question, previousQuestion, PlayerLocale.ZH_CN);
    }

    @Override
    public AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage) {
        return answerForPublicReader(documentVersionId, question, previousQuestion, outputLanguage, null);
    }

    @Override
    public AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage,
            PublicLearningIntent learningIntent) {
        return answerForPublicReader(
                documentVersionId,
                question,
                previousQuestion,
                outputLanguage,
                learningIntent,
                null);
    }

    @Override
    public AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage,
            PublicLearningIntent learningIntent,
            Set<Integer> allowedPublicPages) {
        PlayerLocale language = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
        AnswerCreation creation = answerWithRun(
                question,
                new QuestionContext(
                        documentVersionId,
                        previousQuestion,
                        learningIntent == null ? null : LearningIntent.valueOf(learningIntent.name()),
                        language,
                        null,
                        allowedPublicPages),
                DocumentNativeToolAccess.PUBLIC_READER,
                null);
        return AnswerOutcomePolicy.publicReaderAnswer(
                creation.assistantRunId(), creation.answer(), question, language);
    }

    public AnswerCreation evaluateWithRun(
            String question, QuestionContext context, String username, UUID evaluationSessionId) {
        return Observation.createNotStarted("rulepilot.answer.evaluation", observations)
                .contextualName("answer-agent-evaluation")
                .observe(() -> answerObserved(
                        question, context, username, evaluationSessionId, ignored -> {}));
    }

    private AnswerCreation answerObserved(
            String question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            Consumer<UUID> runStarted) {
        if (context == null) throw new IllegalArgumentException("answer context is required");
        UUID subjectId = gameSessionId == null ? context.documentVersionId() : gameSessionId;
        RunSnapshot run = runLifecycle.start(AssistantRunMode.QUESTION_ANSWER, subjectId, username);
        AnswerRunProgressPolicy.Tracker progress = new AnswerRunProgressPolicy.Tracker();
        try {
            runStarted.accept(run.id());
        } catch (RuntimeException disconnected) {
            LOGGER.debug("Answer progress listener disconnected after run creation");
        }
        try {
            NativeRuleAnswerAgent.AgentOutcome outcome = answerAgent.answer(
                    question, context, username, gameSessionId, run.id());
            progress.reached(AnswerRunProgressPolicy.ExecutionPhase.AGENT_RUNNING);
            runLifecycle.finish(run, outcome.answer(), progress.phase());
            return new AnswerCreation(run.id(), outcome.answer());
        } catch (RuntimeException failure) {
            runLifecycle.fail(
                    run,
                    progress.phase(),
                    "QUESTION_AGENT_FAILED",
                    "Question Agent failed safely",
                    failure);
            throw failure;
        }
    }

    public record AnswerCreation(UUID assistantRunId, StructuredRuleAnswer answer) {
        public AnswerCreation {
            if (assistantRunId == null || answer == null) {
                throw new IllegalArgumentException("answer creation is invalid");
            }
        }
    }
}
