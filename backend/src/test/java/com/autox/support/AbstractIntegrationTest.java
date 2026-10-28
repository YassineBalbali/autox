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

    /**
     * Production's version, pinned by digest. Neon runs 16.15. A floating tag such as
     * {@code 16-alpine} moves to the next minor release without a commit, and the planner can
     * choose differently between releases: a test that reads a query plan would then pass on
     * one machine and fail on another for no change in the code. Move this with production.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            // postgres:16.15-alpine (PostgreSQL 16.15). By digest alone: Testcontainers does not
            // read a reference with both a tag and a digest as the postgres image.
            "postgres@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab")
            .withDatabaseName("autox")
            .withUsername("autox")
            .withPassword("autox");

    /**
     * Real S3-compatible storage. There is no {@code @ServiceConnection} for it, so the
     * endpoint is injected as properties below — and that is the point: the upload flow is
     * only meaningful against something that actually enforces pre-signed URLs.
     */
    static final org.testcontainers.containers.MinIOContainer MINIO =
            new org.testcontainers.containers.MinIOContainer(
                    org.testcontainers.utility.DockerImageName.parse("chainguard/minio@sha256:e7ca559d9f7c0b5f24f5f669bb92f40f3ca88d56273b808bf3a7c116c17d2ffa")
                            // The official minio/minio repository is no longer anonymously
                            // pullable; this is the same server from a mirror that is.
                            .asCompatibleSubstituteFor("minio/minio"))
                    .withUserName("testaccess")
                    .withPassword("testsecret123")
                    // This image's default user cannot write the data directory.
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("0"));

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void storageProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("autox.storage.endpoint", MINIO::getS3URL);
        registry.add("autox.storage.access-key", MINIO::getUserName);
        registry.add("autox.storage.secret-key", MINIO::getPassword);
        registry.add("autox.storage.bucket", () -> "autox-test");
        registry.add("autox.storage.path-style-access", () -> true);
        registry.add("autox.storage.create-bucket-if-missing", () -> true);
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
            // 14 digits, the French SIRET shape. The API stores it as free text; this is
            // only here so the fixture looks like something a real dealer would send.
            payload.put("registrationNumber", "81234567800012");
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

    /**
     * Gives a listing a confirmed photo so it can be published, without the cost of a real
     * upload to object storage.
     *
     * <p>Publication only asks whether a READY image exists, so inserting the row directly is
     * enough and keeps tests that are about the lifecycle from paying for image processing.
     * The upload path itself is covered properly in {@code ListingImageIntegrationTest}.
     */
    protected void attachReadyPhoto(String listingId) {
        UUID imageId = com.autox.common.UuidV7.generate();
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listing_image WHERE listing_id = ?::uuid", Integer.class, listingId);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO listing_image (id, listing_id, position, status, storage_key,
                                           content_type, byte_size, width, height, uploaded_at)
                VALUES (?, ?::uuid, 0, 'READY', ?, 'image/jpeg', 12345, 1600, 1200, now())
                """, imageId, listingId, "listings/" + listingId + "/" + imageId + "/original.jpg");
    }
}
