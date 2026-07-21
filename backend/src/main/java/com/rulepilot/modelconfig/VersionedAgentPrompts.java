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
    private final String atomicCriticSystem;
    private final String objectiveCoverageCriticSystem;
    private final String criticUser;
    private final String structuredOutputRepair;

    public VersionedAgentPrompts(
            @Value("classpath:prompts/teaching-agent-v16-system.txt") Resource teachingSystem,
            @Value("classpath:prompts/teaching-agent-v9-user.txt") Resource teachingUser,
            @Value("classpath:prompts/teaching-outline-v5-system.txt") Resource teachingOutlineSystem,
            @Value("classpath:prompts/teaching-outline-v3-user.txt") Resource teachingOutlineUser,
            @Value("classpath:prompts/rule-answer-agent-v5-system.txt") Resource answerSystem,
            @Value("classpath:prompts/rule-answer-agent-v4-user.txt") Resource answerUser,
            @Value("classpath:prompts/rule-answer-retrieval-rewrite-v1-system.txt") Resource answerRetrievalRewriteSystem,
            @Value("classpath:prompts/rule-answer-retrieval-rewrite-v1-user.txt") Resource answerRetrievalRewriteUser,
            @Value("classpath:prompts/content-critic-v7-system.txt") Resource criticSystem,
            @Value("classpath:prompts/atomic-content-critic-v3-system.txt") Resource atomicCriticSystem,
            @Value("classpath:prompts/objective-coverage-critic-v3-system.txt") Resource objectiveCoverageCriticSystem,
            @Value("classpath:prompts/content-critic-v4-user.txt") Resource criticUser,
            @Value("classpath:prompts/structured-output-repair-v1.txt") Resource structuredOutputRepair)
            throws IOException {
        this.teachingSystem = read(teachingSystem);
        this.teachingUser = read(teachingUser);
        this.teachingOutlineSystem = read(teachingOutlineSystem);
        this.teachingOutlineUser = read(teachingOutlineUser);
        this.answerSystem = read(answerSystem);
        this.answerUser = read(answerUser);
        this.answerRetrievalRewriteSystem = read(answerRetrievalRewriteSystem);
        this.answerRetrievalRewriteUser = read(answerRetrievalRewriteUser);
        this.criticSystem = read(criticSystem);
        this.atomicCriticSystem = read(atomicCriticSystem);
        this.objectiveCoverageCriticSystem = read(objectiveCoverageCriticSystem);
        this.criticUser = read(criticUser);
        this.structuredOutputRepair = read(structuredOutputRepair);
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

    private static String read(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8).strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("agent prompt resource must not be blank");
        }
        return content;
    }
}
