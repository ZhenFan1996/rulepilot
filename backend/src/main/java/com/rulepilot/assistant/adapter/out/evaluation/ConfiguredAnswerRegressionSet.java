package com.rulepilot.assistant.adapter.out.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AnswerRegressionSet;
import com.rulepilot.assistant.domain.AnswerRegressionCase;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ConfiguredAnswerRegressionSet implements AnswerRegressionSet {

    private final String name;
    private final List<AnswerRegressionCase> cases;

    public ConfiguredAnswerRegressionSet(
            @Value("${rulepilot.answer-regression.dataset:}") String dataset,
            @Value("${rulepilot.answer-regression.name:external-answer-regressions}") String name) {
        this.name = name == null || name.isBlank() ? "external-answer-regressions" : name.strip();
        if (dataset == null || dataset.isBlank()) {
            cases = List.of();
            return;
        }
        Resource resource = new DefaultResourceLoader().getResource(dataset.strip());
        try (InputStream input = resource.getInputStream()) {
            cases = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load configured answer regression set", exception);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("configured answer regression set must not be empty");
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<AnswerRegressionCase> cases() {
        return cases;
    }
}
