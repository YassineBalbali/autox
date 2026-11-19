package com.autox.assistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * With the switch off, which is the default and production's setting, the assistant is not there:
 * both endpoints answer 404, nothing is counted and no planner is called.
 */
class AssistantDisabledIntegrationTest extends AbstractIntegrationTest {

    private static final String TURN = "{\"message\": \"Une 208 essence\"}";

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("answers 404 on both endpoints by default, and counts nothing")
    void offByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/capabilities"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/v1/assistant/turns").contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM assistant_usage", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM throttle_counter WHERE scope LIKE 'ASSISTANT%'", Integer.class)).isZero();
    }

    @Nested
    @TestPropertySource(properties = "autox.assistant.enabled = true")
    class SwitchedOnWithoutAModel {

        @Test
        @DisplayName("is still 404 when switched on without a model provider: there is nothing to plan with")
        void noPlanner() throws Exception {
            mockMvc.perform(get("/api/v1/assistant/capabilities")).andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/assistant/turns").contentType(MediaType.APPLICATION_JSON).content(TURN))
                    .andExpect(status().isNotFound());
        }
    }
}
