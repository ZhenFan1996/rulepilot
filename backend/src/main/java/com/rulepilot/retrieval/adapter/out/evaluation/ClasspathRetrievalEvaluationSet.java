package com.rulepilot.retrieval.adapter.out.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.retrieval.RetrievalEvaluationSet;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ClasspathRetrievalEvaluationSet implements RetrievalEvaluationSet {

    private static final String DATASET = "evaluation/starter-game-questions.json";
    private final List<RetrievalEvaluationSample> samples;

    public ClasspathRetrievalEvaluationSet() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = new ClassPathResource(DATASET).getInputStream()) {
            samples = List.copyOf(objectMapper.readValue(input, new TypeReference<>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load retrieval evaluation set " + DATASET, exception);
        }
        if (samples.size() != 30) {
            throw new IllegalStateException("retrieval evaluation set must contain exactly 30 samples");
        }
    }

    @Override
    public String name() {
        return "starter-game-v1";
    }

    @Override
    public List<RetrievalEvaluationSample> samples() {
        return samples;
    }
}
