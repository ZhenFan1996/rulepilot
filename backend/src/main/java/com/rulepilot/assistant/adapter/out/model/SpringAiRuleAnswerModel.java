package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiRuleAnswerModel implements RuleAnswerModel {

    private final RuntimeModelConfiguration models;
    private final FakeRuleAnswerModel fakeModel;
    private final VersionedAgentPrompts prompts;

    public SpringAiRuleAnswerModel(
            RuntimeModelConfiguration models, FakeRuleAnswerModel fakeModel, VersionedAgentPrompts prompts) {
        this.models = models;
        this.fakeModel = fakeModel;
        this.prompts = prompts;
    }

    @Override
    public String providerId() {
        return models.providerFor(Role.ANSWER);
    }

    @Override
    public ModelDraft compose(ModelRequest request) {
        if (models.usesFake(Role.ANSWER)) {
            return fakeModel.compose(request);
        }
        RuntimeException firstFailure;
        try {
            return composeOnce(request, "");
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            firstFailure = exception;
        }
        try {
            return composeOnce(request, prompts.structuredOutputRepair());
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            exception.addSuppressed(firstFailure);
            throw exception;
        }
    }

    private ModelDraft composeOnce(ModelRequest request, String repairInstruction) {
        return ChatClient.create(models.modelFor(Role.ANSWER)).prompt()
                .system(prompts.answerSystem())
                .user(user -> user.text(prompts.answerUser())
                        .param("question", request.question())
                        .param("questionType", request.questionType().name())
                        .param("lessonSection", request.context().currentLessonSection())
                        .param("gamePhase", request.context().gamePhase())
                        .param("playerCount", request.context().playerCountForPrompt())
                        .param("activeExpansionCount", request.context().activeExpansionCount())
                        .param("previousQuestion", request.context().previousQuestion())
                        .param("evidence", request.evidence())
                        .param("repair", repairInstruction))
                .call()
                .entity(ModelDraft.class);
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
