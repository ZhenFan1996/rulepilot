package com.rulepilot.retrieval;

import com.rulepilot.retrieval.domain.RetrievalEvaluationSample;
import java.util.List;

public interface RetrievalEvaluationSet {

    String name();

    List<RetrievalEvaluationSample> samples();
}
