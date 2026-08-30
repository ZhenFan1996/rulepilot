package com.rulepilot.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class VersionedAgentPrompts {

    private final String focusedTeachingSystem;
    private final String teachingUser;
    private final String criticSystem;
    private final String lessonStructureCriticSystem;
    private final String atomicCriticSystem;
    private final String objectiveCoverageCriticSystem;
    private final String criticUser;
    private final String atomicCriticUser;
    private final String structuredOutputRepair;
    private final String criticOutputRepair;
    private final String lessonLocalizationSystem;
    private final String lessonLocalizationUser;

    public VersionedAgentPrompts(
            @Value("classpath:prompts/teaching-agent-v45-focused-source-contract-system.txt") Resource focusedTeachingSystem,
            @Value("classpath:prompts/teaching-agent-v44-quantitative-aggregation-system.txt") Resource teachingQuantitativeAggregation,
            @Value("classpath:prompts/teaching-agent-v12-user.txt") Resource teachingUser,
            @Value("classpath:prompts/content-critic-v34-focused-runtime-system.txt") Resource focusedCriticSystem,
            @Value("classpath:prompts/content-critic-v35-quantitative-aggregation-system.txt") Resource criticQuantitativeAggregation,
            @Value("classpath:prompts/content-critic-v36-claim-aspect-contract-system.txt") Resource criticClaimAspectContract,
            @Value("classpath:prompts/content-critic-v37-answer-source-authority-system.txt") Resource criticAnswerSourceAuthority,
            @Value("classpath:prompts/content-critic-v38-exact-output-contract-system.txt") Resource criticExactOutputContract,
            @Value("classpath:prompts/atomic-content-critic-v3-system.txt") Resource atomicCriticSystem,
            @Value("classpath:prompts/atomic-content-critic-v5-claim-aspect-system.txt") Resource atomicClaimAspectSystem,
            @Value("classpath:prompts/atomic-content-critic-v6-answer-source-authority-system.txt") Resource atomicAnswerSourceAuthority,
            @Value("classpath:prompts/objective-coverage-critic-v3-system.txt") Resource objectiveCoverageCriticSystem,
            @Value("classpath:prompts/content-critic-v4-user.txt") Resource criticUser,
            @Value("classpath:prompts/atomic-content-critic-v7-confirmed-only-user.txt") Resource atomicCriticUser,
            @Value("classpath:prompts/atomic-content-critic-v8-claim-aspect-confirmed-only-user.txt") Resource atomicClaimAspectUser,
            @Value("classpath:prompts/structured-output-repair-v1.txt") Resource structuredOutputRepair,
            @Value("classpath:prompts/content-critic-output-repair-v1.txt") Resource criticOutputRepair,
            @Value("classpath:prompts/content-critic-output-repair-v2-claim-aspect.txt") Resource criticClaimAspectRepair,
            @Value("classpath:prompts/lesson-localization-v2-system.txt") Resource lessonLocalizationSystem,
            @Value("classpath:prompts/lesson-localization-v1-user.txt") Resource lessonLocalizationUser)
            throws IOException {
        this.focusedTeachingSystem = combined(focusedTeachingSystem, teachingQuantitativeAggregation);
        this.teachingUser = read(teachingUser);
        this.criticSystem = combined(
                focusedCriticSystem,
                criticQuantitativeAggregation,
                criticClaimAspectContract,
                criticAnswerSourceAuthority,
                criticExactOutputContract);
        this.lessonStructureCriticSystem = combined(
                focusedCriticSystem,
                criticQuantitativeAggregation,
                criticClaimAspectContract,
                criticExactOutputContract);
        this.atomicCriticSystem = combined(
                atomicCriticSystem,
                atomicClaimAspectSystem,
                atomicAnswerSourceAuthority,
                criticExactOutputContract);
        this.objectiveCoverageCriticSystem = combined(
                objectiveCoverageCriticSystem,
                criticClaimAspectContract,
                criticExactOutputContract);
        this.criticUser = read(criticUser);
        this.atomicCriticUser = combined(atomicCriticUser, atomicClaimAspectUser);
        this.structuredOutputRepair = read(structuredOutputRepair);
        this.criticOutputRepair = combined(criticOutputRepair, criticClaimAspectRepair);
        this.lessonLocalizationSystem = read(lessonLocalizationSystem);
        this.lessonLocalizationUser = read(lessonLocalizationUser);
    }

    /** Runtime teaching prompt: one compact contract instead of every historical prompt revision. */
    public String teachingRuntimeSystem() {
        return focusedTeachingSystem;
    }

    public String teachingUser() {
        return teachingUser;
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

    public String atomicCriticUser() {
        return atomicCriticUser;
    }

    public String structuredOutputRepair() {
        return structuredOutputRepair;
    }

    public String criticOutputRepair() {
        return criticOutputRepair;
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
