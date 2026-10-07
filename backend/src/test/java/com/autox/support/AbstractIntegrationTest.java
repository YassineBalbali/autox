package com.autox.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.autox.reference.CountryRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared harness: a real PostgreSQL with the real Flyway migrations applied, and the
 * full Spring Security filter chain in front of the controllers. Nothing about
 * authentication is mocked — a test that passes here exercises the same code path a
 * phone would.
 *
 * <p>The container is {@code static} and never stopped, so all test classes share one
 * database and pay the startup cost once. Each test truncates the tables it writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autox")
            .withUsername("autox")
            .withPassword("autox");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected AppUserRepository users;
    @Autowired protected CountryRepository countries;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected TestDatabaseCleaner cleaner;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetUserData() {
        // Reference data from V5 is left alone; only rows tests create are removed.
        cleaner.truncateTransactionalTables();
    }

    // --- request helpers --------------------------------------------------

    protected MvcResult postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Registers an account and returns the auth response body. */
    protected JsonNode register(String email, UserRole role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationPayload(email, role))))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result);
    }

    protected java.util.Map<String, Object> registrationPayload(String email, UserRole role) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("email", email);
        payload.put("password", "correct-horse-battery");
        payload.put("role", role.name());
        payload.put("displayName", "Test " + role.name());
        payload.put("countryCode", "FR");
        payload.put("locale", "fr-FR");
        if (role == UserRole.PROFESSIONNEL) {
            payload.put("companyName", "Garage du Test");
        }
        return payload;
    }

    /** Creates a user directly, for roles and states registration cannot produce. */
    protected AppUser createUser(String email, UserRole role, UserStatus status) {
        AppUser user = AppUser.register(email, passwordEncoder.encode("correct-horse-battery"), role,
                "Direct " + role.name(), countries.findEnabledByCode("FR").orElseThrow(), "fr-FR");
        user.setStatus(status);
        return users.saveAndFlush(user);
    }

    protected String bearer(JsonNode authResponse) {
        return "Bearer " + authResponse.get("accessToken").asText();
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@autox.test";
    }
}
