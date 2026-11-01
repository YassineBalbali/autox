package com.autox.search.interpret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The default: {@code autox.ai.provider} unset, so natural-language search is off. The suite's
 * shared configuration, so no property is set here — this is what every server without a
 * provider runs.
 */
class SearchInterpretDisabledIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("capabilities say off, so the web app hides the entry")
    void capabilitiesSayOff() throws Exception {
        mockMvc.perform(get("/api/v1/search/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpret").value(false));
    }

    @Test
    @DisplayName("a request anyway is 404, and counts nothing against the address")
    void interpretIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/search/interpret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"SUV diesel\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Integer counters = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM throttle_counter WHERE scope = 'SEARCH_INTERPRET'", Integer.class);
        assertThat(counters).isZero();
    }
}
