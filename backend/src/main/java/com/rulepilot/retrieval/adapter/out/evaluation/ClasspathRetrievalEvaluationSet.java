package com.rulepilot.retrieval.adapter.out.evaluation;

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
    private final String name;
    private final String sourceSha256;
    private final List<RetrievalEvaluationSample> samples;

    public ClasspathRetrievalEvaluationSet() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = new ClassPathResource(DATASET).getInputStream()) {
            Fixture fixture = objectMapper.readValue(input, Fixture.class);
            name = fixture.name();
            sourceSha256 = fixture.sourceSha256();
            samples = List.copyOf(fixture.samples());
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load retrieval evaluation set " + DATASET, exception);
        }
        if (name == null || name.isBlank()
                || sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")
                || samples.isEmpty()) {
            throw new IllegalStateException("retrieval evaluation fixture metadata is invalid");
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String sourceSha256() {
        return sourceSha256;
    }

    @Override
    public List<RetrievalEvaluationSample> samples() {
        return samples;
    }

    private record Fixture(
            String schemaVersion,
            String name,
            String sourceSha256,
            List<RetrievalEvaluationSample> samples) {

        private Fixture {
            if (!"rulepilot.retrieval-evaluation/v2".equals(schemaVersion)) {
                throw new IllegalArgumentException("retrieval evaluation fixture schema is unsupported");
            }
        }
    }
}
