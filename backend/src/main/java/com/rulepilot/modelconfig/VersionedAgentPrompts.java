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
    private final String criticSystem;
    private final String criticUser;
    private final String structuredOutputRepair;

    public VersionedAgentPrompts(
            @Value("classpath:prompts/teaching-agent-v10-system.txt") Resource teachingSystem,
            @Value("classpath:prompts/teaching-agent-v8-user.txt") Resource teachingUser,
            @Value("classpath:prompts/teaching-outline-v2-system.txt") Resource teachingOutlineSystem,
            @Value("classpath:prompts/teaching-outline-v2-user.txt") Resource teachingOutlineUser,
            @Value("classpath:prompts/rule-answer-agent-v4-system.txt") Resource answerSystem,
            @Value("classpath:prompts/rule-answer-agent-v4-user.txt") Resource answerUser,
            @Value("classpath:prompts/content-critic-v5-system.txt") Resource criticSystem,
            @Value("classpath:prompts/content-critic-v4-user.txt") Resource criticUser,
            @Value("classpath:prompts/structured-output-repair-v1.txt") Resource structuredOutputRepair)
            throws IOException {
        this.teachingSystem = read(teachingSystem);
        this.teachingUser = read(teachingUser);
        this.teachingOutlineSystem = read(teachingOutlineSystem);
        this.teachingOutlineUser = read(teachingOutlineUser);
        this.answerSystem = read(answerSystem);
        this.answerUser = read(answerUser);
        this.criticSystem = read(criticSystem);
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

    public String criticSystem() {
        return criticSystem;
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
