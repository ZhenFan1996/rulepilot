package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.AnswerRegressionCase;
import java.util.List;

public interface AnswerRegressionSet {

    String name();

    List<AnswerRegressionCase> cases();
}
