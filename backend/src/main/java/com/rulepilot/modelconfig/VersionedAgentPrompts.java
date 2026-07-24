package com.rulepilot.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class VersionedAgentPrompts {

    private final String teachingSystem;
    private final String teachingUser;
    private final String teachingOutlineSystem;
    private final String teachingOutlineUser;
    private final String answerSystem;
    private final String answerUser;
    private final String answerRetrievalRewriteSystem;
    private final String answerRetrievalRewriteUser;
    private final String criticSystem;
    private final String lessonStructureCriticSystem;
    private final String atomicCriticSystem;
    private final String objectiveCoverageCriticSystem;
    private final String criticUser;
    private final String structuredOutputRepair;
    private final String lessonLocalizationSystem;
    private final String lessonLocalizationUser;

    public VersionedAgentPrompts(
            @Value("classpath:prompts/teaching-agent-v16-system.txt") Resource teachingSystem,
            @Value("classpath:prompts/teaching-agent-v17-fidelity-system.txt") Resource teachingFidelity,
            @Value("classpath:prompts/teaching-agent-v18-visual-fit-system.txt") Resource teachingVisualFit,
            @Value("classpath:prompts/teaching-agent-v19-player-language-system.txt") Resource teachingPlayerLanguage,
            @Value("classpath:prompts/teaching-agent-v20-visual-output-contract-system.txt") Resource teachingVisualOutput,
            @Value("classpath:prompts/teaching-agent-v21-complete-instructions-system.txt") Resource teachingCompleteInstructions,
            @Value("classpath:prompts/teaching-agent-v22-ordered-procedure-fidelity-system.txt") Resource teachingProcedureFidelity,
            @Value("classpath:prompts/teaching-agent-v23-chapter-scope-map-system.txt") Resource teachingChapterScope,
            @Value("classpath:prompts/teaching-agent-v24-actor-and-example-fidelity-system.txt") Resource teachingActorAndExampleFidelity,
            @Value("classpath:prompts/teaching-agent-v9-user.txt") Resource teachingUser,
            @Value("classpath:prompts/teaching-outline-v6-system.txt") Resource teachingOutlineSystem,
            @Value("classpath:prompts/teaching-outline-v7-fidelity-system.txt") Resource teachingOutlineFidelity,
            @Value("classpath:prompts/teaching-outline-v8-visual-density-system.txt") Resource teachingOutlineVisualDensity,
            @Value("classpath:prompts/teaching-outline-v9-core-evidence-system.txt") Resource teachingOutlineCoreEvidence,
            @Value("classpath:prompts/teaching-outline-v10-chapter-ownership-system.txt") Resource teachingOutlineChapterOwnership,
            @Value("classpath:prompts/teaching-outline-v11-visual-coverage-system.txt") Resource teachingOutlineVisualCoverage,
            @Value("classpath:prompts/teaching-outline-v3-user.txt") Resource teachingOutlineUser,
            @Value("classpath:prompts/rule-answer-agent-v6-system.txt") Resource answerSystem,
            @Value("classpath:prompts/rule-answer-agent-v7-fidelity-system.txt") Resource answerFidelity,
            @Value("classpath:prompts/rule-answer-agent-v8-direct-rulings-system.txt") Resource answerDirectRulings,
            @Value("classpath:prompts/rule-answer-agent-v9-prohibition-fidelity-system.txt") Resource answerProhibitionFidelity,
            @Value("classpath:prompts/rule-answer-agent-v10-completeness-boundary-system.txt") Resource answerCompletenessBoundary,
            @Value("classpath:prompts/rule-answer-agent-v11-scope-discipline-system.txt") Resource answerScopeDiscipline,
            @Value("classpath:prompts/rule-answer-agent-v12-direct-clause-citations-system.txt") Resource answerDirectClauseCitations,
            @Value("classpath:prompts/rule-answer-agent-v13-grounded-application-system.txt") Resource answerGroundedApplication,
            @Value("classpath:prompts/rule-answer-agent-v4-user.txt") Resource answerUser,
            @Value("classpath:prompts/rule-answer-retrieval-rewrite-v1-system.txt") Resource answerRetrievalRewriteSystem,
            @Value("classpath:prompts/rule-answer-retrieval-rewrite-v1-user.txt") Resource answerRetrievalRewriteUser,
            @Value("classpath:prompts/content-critic-v7-system.txt") Resource criticSystem,
            @Value("classpath:prompts/content-critic-v8-fidelity-system.txt") Resource criticFidelity,
            @Value("classpath:prompts/content-critic-v9-answer-scope-system.txt") Resource criticAnswerScope,
            @Value("classpath:prompts/content-critic-v10-lesson-structure-system.txt") Resource criticLessonStructure,
            @Value("classpath:prompts/content-critic-v11-actor-and-example-fidelity-system.txt") Resource criticActorAndExampleFidelity,
            @Value("classpath:prompts/atomic-content-critic-v3-system.txt") Resource atomicCriticSystem,
            @Value("classpath:prompts/objective-coverage-critic-v3-system.txt") Resource objectiveCoverageCriticSystem,
            @Value("classpath:prompts/content-critic-v4-user.txt") Resource criticUser,
            @Value("classpath:prompts/structured-output-repair-v1.txt") Resource structuredOutputRepair,
            @Value("classpath:prompts/lesson-localization-v1-system.txt") Resource lessonLocalizationSystem,
            @Value("classpath:prompts/lesson-localization-v1-user.txt") Resource lessonLocalizationUser)
            throws IOException {
        this.teachingSystem = combined(
                teachingSystem,
                teachingFidelity,
                teachingVisualFit,
                teachingPlayerLanguage,
                teachingVisualOutput,
                teachingCompleteInstructions,
                teachingProcedureFidelity,
                teachingChapterScope,
                teachingActorAndExampleFidelity);
        this.teachingUser = read(teachingUser);
        this.teachingOutlineSystem = combined(
                teachingOutlineSystem,
                teachingOutlineFidelity,
                teachingOutlineVisualDensity,
                teachingOutlineCoreEvidence,
                teachingOutlineChapterOwnership,
                teachingOutlineVisualCoverage);
        this.teachingOutlineUser = read(teachingOutlineUser);
        this.answerSystem = combined(
                answerSystem,
                answerFidelity,
                answerDirectRulings,
                answerProhibitionFidelity,
                answerCompletenessBoundary,
                answerScopeDiscipline,
                answerDirectClauseCitations,
                answerGroundedApplication);
        this.answerUser = read(answerUser);
        this.answerRetrievalRewriteSystem = read(answerRetrievalRewriteSystem);
        this.answerRetrievalRewriteUser = read(answerRetrievalRewriteUser);
        this.criticSystem = combined(
                criticSystem,
                criticFidelity,
                criticAnswerScope,
                criticLessonStructure,
                criticActorAndExampleFidelity);
        this.lessonStructureCriticSystem = read(criticLessonStructure);
        this.atomicCriticSystem = read(atomicCriticSystem);
        this.objectiveCoverageCriticSystem = read(objectiveCoverageCriticSystem);
        this.criticUser = read(criticUser);
        this.structuredOutputRepair = read(structuredOutputRepair);
        this.lessonLocalizationSystem = read(lessonLocalizationSystem);
        this.lessonLocalizationUser = read(lessonLocalizationUser);
    }

    public String teachingSystem() {
        return teachingSystem;
    }

    public String teachingUser() {
        return teachingUser;
    }

    public String teachingOutlineSystem() {
        return teachingOutlineSystem;
    }

    public String teachingOutlineUser() {
        return teachingOutlineUser;
    }

    public String answerSystem() {
        return answerSystem;
    }

    public String answerUser() {
        return answerUser;
    }

    public String answerRetrievalRewriteSystem() {
        return answerRetrievalRewriteSystem;
    }

    public String answerRetrievalRewriteUser() {
        return answerRetrievalRewriteUser;
    }

    public String criticSystem() {
        return criticSystem;
    }

    public String lessonStructureCriticSystem() {
        return lessonStructureCriticSystem;
    }

    public String atomicCriticSystem() {
        return atomicCriticSystem;
    }

    public String objectiveCoverageCriticSystem() {
        return objectiveCoverageCriticSystem;
    }

    public String criticUser() {
        return criticUser;
    }

    public String structuredOutputRepair() {
        return structuredOutputRepair;
    }

    public String lessonLocalizationSystem() {
        return lessonLocalizationSystem;
    }

    public String lessonLocalizationUser() {
        return lessonLocalizationUser;
    }

    private static String read(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8).strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("agent prompt resource must not be blank");
        }
        return content;
    }

    private static String combined(Resource base, Resource... revisions) throws IOException {
        StringBuilder combined = new StringBuilder(read(base));
        for (Resource revision : revisions) {
            combined.append("\n\n").append(read(revision));
        }
        return combined.toString();
    }
}
