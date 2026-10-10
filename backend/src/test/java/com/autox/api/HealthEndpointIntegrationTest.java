package com.autox.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The health endpoint, and — more importantly — everything actuator is not allowed to say.
 *
 * <p>A health check has to be callable by a load balancer, which has no account, so it is
 * public. That makes it the one anonymous window into the deployment, and the assertions
 * below are mostly about keeping that window opaque.
 */
class HealthEndpointIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("health is public and says only whether the instance is up")
    void healthIsPublicAndOpaque() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // No component breakdown: no database vendor, no disk figures, no bucket
                // names. An anonymous caller learns that the service is serving, and that
                // is the whole contract.
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PostgreSQL"))))
                // The whole body, enumerated. `groups` is the names of the probe groups and
                // nothing more — every Spring Boot application has those two — and actuator
                // offers no way to suppress it. Asserting the key set is how a future
                // version adding a field gets noticed here rather than in production.
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.groups").value(
                        org.hamcrest.Matchers.contains("liveness", "readiness")));
    }

    @Test
    @DisplayName("the liveness and readiness probes are available")
    void probesAreAvailable() throws Exception {
        // Separate signals on purpose: liveness says the process is alive, readiness says
        // it can serve. Restarting an instance that is merely waiting on the database is
        // the wrong response, and conflating the two is how that happens.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("nothing else actuator offers is exposed")
    void onlyHealthIsExposed() throws Exception {
        // Each of these is either a credential store or a map of the system. The exposure
        // list is an allow-list of one, and this is the test that notices if that changes.
        //
        // The assertion is "not served", not a specific status: an unexposed endpoint is
        // not mapped at all, so what answers is the API's catch-all authentication rule
        // with a 401. Either way the body is not actuator's.
        for (String endpoint : new String[]{"env", "configprops", "beans", "mappings",
                "metrics", "loggers", "heapdump", "threaddump", "flyway"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().is4xxClientError());
        }
    }
}
