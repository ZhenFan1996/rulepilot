package com.rulepilot.assistant.adapter.out.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AnswerRegressionSet;
import com.rulepilot.assistant.domain.AnswerRegressionCase;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ClasspathAnswerRegressionSet implements AnswerRegressionSet {

    private static final String DATASET = "evaluation/seti-answer-regressions-v1.json";
    private final List<AnswerRegressionCase> cases;

    public ClasspathAnswerRegressionSet() {
        try (InputStream input = new ClassPathResource(DATASET).getInputStream()) {
            cases = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load answer regression set " + DATASET, exception);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("answer regression set must not be empty");
        }
    }

    @Override
    public String name() {
        return "seti-table-rulings-v1";
    }

    @Override
    public List<AnswerRegressionCase> cases() {
        return cases;
    }
}
