package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.relay.exceptions.ApiException;
import com.relay.services.WorkflowEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for workflow validation; no database required. */
class WorkflowEngineTests {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final WorkflowEngine engine = new WorkflowEngine(null, mapper);

    private JsonNode json(String raw) { return mapper.readTree(raw); }

    @Test
    void acceptsAllSupportedStepTypes() {
        engine.validate(json("""
                {"steps":[
                  {"id":"a","type":"create","resource":"t","data":{"x":"{{input.x}}"}},
                  {"id":"b","type":"get","resource":"t","recordId":"{{steps.a.id}}"},
                  {"id":"c","type":"list","resource":"t","filter":{"x":1},"limit":5},
                  {"id":"d","type":"update","resource":"t","recordId":"{{steps.a.id}}","data":{"x":2}},
                  {"id":"e","type":"delete","resource":"t","recordId":"{{steps.a.id}}"}],
                 "response":{"status":201,"body":"{{steps.c.data}}"}}"""));
    }

    @Test
    void rejectsUnknownKeywordsDuplicateIdsAndMissingFields() {
        assertThatThrownBy(() -> engine.validate(json("""
                {"steps":[{"id":"a","type":"create","resource":"t"},
                          {"id":"a","type":"shell","resource":"t"},
                          {"id":"c","type":"get","resource":"t","code":"x"}],
                 "response":{"status":500},"script":"x"}""")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).containsKeys(
                        "/script", "/steps/0/data", "/steps/1/id", "/steps/1/type",
                        "/steps/2/code", "/steps/2/recordId", "/response/status"));
    }

    @Test
    void limitsStepCount() {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) steps.append(',');
            steps.append("{\"id\":\"s").append(i).append("\",\"type\":\"list\",\"resource\":\"t\"}");
        }
        assertThatThrownBy(() -> engine.validate(json("{\"steps\":[" + steps + "]}")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsNonObjectDefinition() {
        assertThatThrownBy(() -> engine.validate(json("[]"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> engine.validate(null)).isInstanceOf(ApiException.class);
    }
}
