package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * With {@code autox.assistant.eval-trace} on, as eval/assistant/run-eval.mjs needs on a local machine,
 * each turn carries its checked plan in a header. Off, the default, it never does
 * ({@code AssistantIntegrationTest}).
 */
@TestPropertySource(properties = {
        "autox.ai.provider = stub",
        "autox.assistant.enabled = true",
        "autox.assistant.tokens-per-minute = 100000000",
        "autox.assistant.eval-trace = true"})
@Import(AssistantIntegrationTest.StubConfiguration.class)
class AssistantEvalTraceIntegrationTest extends AbstractIntegrationTest {

    @Autowired StubAssistantPlanner planner;
    @Autowired AssistantPacing pacing;

    @Test
    @DisplayName("sends the turn's plan, its answer's blocks and its tokens in the eval header")
    void traced() throws Exception {
        planner.reset();
        pacing.clear();
        planner.plans(objectMapper.readTree(
                "{\"intent\": \"HELP\", \"calls\": [{\"tool\": \"search_help\", \"query\": \"supprimer mon compte\"}]}"));

        String header = mockMvc.perform(post("/api/v1/assistant/turns").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Comment supprimer mon compte ?\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(EvalTrace.HEADER);

        JsonNode trace = objectMapper.readTree(header);
        assertThat(trace.get("intent").asText()).isEqualTo("HELP");
        assertThat(trace.at("/calls/0/tool").asText()).isEqualTo("search_help");
        assertThat(trace.at("/calls/0/query").asText()).isEqualTo("supprimer mon compte");
        assertThat(trace.at("/blocks/0").asText()).isEqualTo("HELP_PASSAGE");
        assertThat(trace.get("attempts").asInt()).isEqualTo(1);
        assertThat(trace.has("tokensIn")).isTrue();
    }
}
