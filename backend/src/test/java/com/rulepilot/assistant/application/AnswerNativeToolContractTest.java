package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeVisualEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerNativeToolContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyAnswerReadToolExplainsWhenToUseItItsAuthorityAndWhenToStop() throws Exception {
        List<NativeAgentTool> tools = List.of(
                new SearchRuleEvidenceNativeTool(mock(AssistantReadTools.class), json),
                new ReadRulePagesNativeTool(mock(AssistantReadTools.class), json),
                new ExpandRuleEvidenceContextNativeTool(mock(AssistantReadTools.class), json),
                new SearchRuleRelationshipsNativeTool(mock(AssistantReadTools.class), json),
                new ReadVisualPageFactsNativeTool(mock(NativeVisualEvidence.class), json));

        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description())
                    .containsIgnoringCase("use")
                    .containsIgnoringCase("active immutable rulebook")
                    .containsIgnoringCase("stop")
                    .hasSizeLessThanOrEqualTo(500);
            JsonNode schema = json.readTree(tool.inputSchema());
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("properties").properties()).allSatisfy(property ->
                    assertThat(property.getValue().path("description").asText())
                            .as(tool.name() + "." + property.getKey())
                            .isNotBlank());
        });
    }
}
