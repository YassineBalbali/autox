package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.AppUser;
import com.autox.user.ProProfileRepository;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The administrators' user management: who may use it, what it shows, and above all its rules. */
class AdminUserIntegrationTest extends AbstractIntegrationTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired ProProfileRepository proProfiles;
    @Autowired AdminUserService service;

    // ---- helpers -------------------------------------------------------------------------

    private JsonNode signIn(AppUser user) throws Exception {
        return json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private AppUser admin() {
        return createUser(uniqueEmail("admin"), UserRole.ADMIN, UserStatus.ACTIVE);
    }

    private AppUser person() {
        return createUser(uniqueEmail("person"), UserRole.PARTICULIER, UserStatus.PENDING_VERIFICATION);
    }

    private ResultActions send(JsonNode session, String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions suspend(JsonNode session, AppUser target) throws Exception {
        return send(session, USERS + "/" + target.getId() + "/suspend", Map.of("reason", "Annonces frauduleuses"));
    }

    private ResultActions reactivate(JsonNode session, AppUser target) throws Exception {
        return send(session, USERS + "/" + target.getId() + "/reactivate", Map.of());
    }

    private ResultActions changeRole(JsonNode session, AppUser target, UserRole role) throws Exception {
        return send(session, USERS + "/" + target.getId() + "/role", Map.of("role", role.name()));
    }

    private ResultActions read(JsonNode session, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(session)));
    }

    /** A published listing, written directly: nothing here depends on how it got published. */
    private UUID listingOf(AppUser seller, String title) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title,
                                     price_amount, price_currency_code, country_code,
                                     postal_code, city, make_text, model_text,
                                     first_registration_year, mileage_km, fuel_type, gearbox,
                                     body_type, vehicle_condition, published_at, expires_at,
                                     created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 'NOT_REVIEWED', ?,
                        1500000, 'EUR', 'FR', '75011', 'Paris', 'Audi', 'A3',
                        2021, 40000, 'PETROL', 'MANUAL', 'HATCHBACK', 'USED',
                        now(), now() + interval '60 days', now(), now())
                """, id, seller.getId(), title);
        return id;
    }

    private String statusOf(AppUser user) {
        return jdbcTemplate.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, user.getId());
    }

    private String roleOf(AppUser user) {
        return jdbcTemplate.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, user.getId());
    }

    private int recordedActions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM admin_user_action", Integer.class);
    }

    // ---- authorization -------------------------------------------------------------------

    @Nested
    @DisplayName("authorization")
    class Authorization {

        private List<ResultActions> everyEndpoint(String authorization, UUID id) throws Exception {
            List<ResultActions> answers = new ArrayList<>();
            for (var request : List.of(
                    get(USERS),
                    get(USERS + "/" + id),
                    post(USERS + "/" + id + "/suspend").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                    post(USERS + "/" + id + "/reactivate").contentType(MediaType.APPLICATION_JSON).content("{}"),
                    post(USERS + "/" + id + "/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))) {
                answers.add(mockMvc.perform(authorization == null ? request : request.header(HttpHeaders.AUTHORIZATION, authorization)));
            }
            return answers;
        }

        @Test
        @DisplayName("every endpoint answers 401 to a visitor with no session")
        void anonymous() throws Exception {
            for (ResultActions answer : everyEndpoint(null, person().getId())) {
                answer.andExpect(status().isUnauthorized());
            }
        }

        @Test
        @DisplayName("every endpoint answers 403 to a private seller and to a dealer, and changes nothing")
        void nonAdmins() throws Exception {
            AppUser target = person();
            for (UserRole role : List.of(UserRole.PARTICULIER, UserRole.PROFESSIONNEL)) {
                JsonNode session = signIn(createUser(uniqueEmail("nosy"), role, UserStatus.ACTIVE));
                for (ResultActions answer : everyEndpoint(bearer(session), target.getId())) {
                    answer.andExpect(status().isForbidden());
                }
            }
            assertThat(statusOf(target)).isEqualTo("PENDING_VERIFICATION");
            assertThat(roleOf(target)).isEqualTo("PARTICULIER");
            assertThat(recordedActions()).isZero();
        }

        @Test
        @DisplayName("a token that still says ADMIN is refused once the account is no longer one")
        void staleAdminToken() throws Exception {
            // Up to fifteen minutes of a token nobody can recall. On these endpoints the row decides.
            AppUser former = admin();
            AppUser another = admin();
            AppUser target = person();
            JsonNode session = signIn(former);
            changeRole(signIn(another), former, UserRole.PARTICULIER).andExpect(status().isOk());

            read(session, USERS).andExpect(status().isForbidden());
            read(session, USERS + "/" + target.getId()).andExpect(status().isForbidden());
            suspend(session, target).andExpect(status().isForbidden());
            assertThat(statusOf(target)).isEqualTo("PENDING_VERIFICATION");
        }

        @Test
        @DisplayName("a suspended administrator cannot use their old token to reactivate themselves or anyone")
        void suspendedAdminToken() throws Exception {
            AppUser suspended = admin();
            AppUser other = admin();
            AppUser target = person();
            JsonNode session = signIn(suspended);
            JsonNode colleague = signIn(other);
            suspend(colleague, suspended).andExpect(status().isOk());
            suspend(colleague, target).andExpect(status().isOk());

            reactivate(session, suspended).andExpect(status().isForbidden());
            reactivate(session, target).andExpect(status().isForbidden());
            assertThat(statusOf(suspended)).isEqualTo("SUSPENDED");
            assertThat(statusOf(target)).isEqualTo("SUSPENDED");
        }

        @Test
        @DisplayName("the public listing page gains no address and no phone number")
        void publicPageHasNoContactDetails() throws Exception {
            AppUser seller = person();
            jdbcTemplate.update("UPDATE app_user SET phone_e164 = '+33612345678' WHERE id = ?", seller.getId());
            UUID listing = listingOf(seller, "Audi A3");

            String body = mockMvc.perform(get("/api/v1/listings/" + listing))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(seller.getEmail()).doesNotContain("+33612345678");
        }
    }

    // ---- the list ------------------------------------------------------------------------

    @Nested
    @DisplayName("the list")
    class TheList {

        @Test
        @DisplayName("shows what the screen needs, counts listings, and never a password hash")
        void row() throws Exception {
            JsonNode session = signIn(admin());
            AppUser seller = person();
            listingOf(seller, "Une");
            UUID deleted = listingOf(seller, "Supprimée");
            jdbcTemplate.update("UPDATE listing SET deleted_at = now() WHERE id = ?", deleted);

            String body = read(session, USERS + "?q=" + seller.getEmail())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(seller.getId().toString()))
                    .andExpect(jsonPath("$.items[0].email").value(seller.getEmail()))
                    .andExpect(jsonPath("$.items[0].displayName").value(seller.getDisplayName()))
                    .andExpect(jsonPath("$.items[0].role").value("PARTICULIER"))
                    .andExpect(jsonPath("$.items[0].status").value("PENDING_VERIFICATION"))
                    .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.items[0].listingCount").value(1))
                    .andExpect(jsonPath("$.items[0].demo").value(false))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
        }

        @Test
        @DisplayName("searches names and addresses whatever the case, and takes % and _ literally")
        void search() throws Exception {
            JsonNode session = signIn(admin());
            AppUser named = person();
            jdbcTemplate.update("UPDATE app_user SET display_name = 'Garage du Lac' WHERE id = ?", named.getId());
            AppUser byAddress = createUser("Zoe_Martin@autox.test", UserRole.PARTICULIER, UserStatus.ACTIVE);
            createUser("zoexmartin@autox.test", UserRole.PARTICULIER, UserStatus.ACTIVE);

            read(session, USERS + "?q=garage DU")
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(named.getId().toString()));
            read(session, USERS + "?q=ZOE_MARTIN")
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(byAddress.getId().toString()));
            read(session, USERS + "?q=%25").andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("filters by role and by status, together")
        void filters() throws Exception {
            JsonNode session = signIn(admin());
            AppUser suspendedDealer = createUser(uniqueEmail("dealer"), UserRole.PROFESSIONNEL, UserStatus.SUSPENDED);
            createUser(uniqueEmail("dealer"), UserRole.PROFESSIONNEL, UserStatus.ACTIVE);
            createUser(uniqueEmail("person"), UserRole.PARTICULIER, UserStatus.SUSPENDED);

            read(session, USERS + "?role=PROFESSIONNEL").andExpect(jsonPath("$.totalElements").value(2));
            read(session, USERS + "?status=SUSPENDED").andExpect(jsonPath("$.totalElements").value(2));
            read(session, USERS + "?role=PROFESSIONNEL&status=SUSPENDED")
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(suspendedDealer.getId().toString()));
            read(session, USERS + "?role=NOPE").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("pages, newest first, and leaves closed accounts out")
        void paging() throws Exception {
            JsonNode session = signIn(admin());
            AppUser oldest = person();
            person();
            AppUser newest = person();
            jdbcTemplate.update("UPDATE app_user SET created_at = now() - interval '2 days' WHERE id = ?", oldest.getId());
            jdbcTemplate.update("UPDATE app_user SET created_at = now() + interval '1 day' WHERE id = ?", newest.getId());
            AppUser closed = person();
            jdbcTemplate.update("UPDATE app_user SET deleted_at = now(), status = 'DELETED' WHERE id = ?", closed.getId());

            read(session, USERS + "?size=2")
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.items[0].id").value(newest.getId().toString()));
            read(session, USERS + "?size=2&page=1")
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[1].id").value(oldest.getId().toString()))
                    .andExpect(jsonPath("$.hasNext").value(false));
            read(session, USERS + "/" + closed.getId()).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("marks demo accounts through the ledger, and filters on them")
        void demo() throws Exception {
            JsonNode session = signIn(admin());
            AppUser demo = person();
            person();

            // Before any seed: no ledger, so nobody is a demo account, and the filter still answers.
            read(session, USERS + "?demo=true").andExpect(jsonPath("$.totalElements").value(0));
            read(session, USERS + "?demo=false").andExpect(jsonPath("$.totalElements").value(3));

            jdbcTemplate.execute("""
                    CREATE TABLE demo_seed_ledger (entity_type VARCHAR(20) NOT NULL, entity_id UUID NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (entity_type, entity_id))""");
            try {
                jdbcTemplate.update("INSERT INTO demo_seed_ledger (entity_type, entity_id) VALUES ('USER', ?)", demo.getId());

                read(session, USERS + "?demo=true")
                        .andExpect(jsonPath("$.totalElements").value(1))
                        .andExpect(jsonPath("$.items[0].id").value(demo.getId().toString()))
                        .andExpect(jsonPath("$.items[0].demo").value(true));
                read(session, USERS + "?demo=false").andExpect(jsonPath("$.totalElements").value(2));
                read(session, USERS + "/" + demo.getId()).andExpect(jsonPath("$.demo").value(true));

                // Nobody can sign in to it: it would be an administrator nobody can be.
                changeRole(session, demo, UserRole.ADMIN)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("DEMO_ACCOUNT_CANNOT_BE_ADMIN"));
                assertThat(roleOf(demo)).isEqualTo("PARTICULIER");
            } finally {
                jdbcTemplate.execute("DROP TABLE demo_seed_ledger");
            }
        }
    }

    // ---- the detail ----------------------------------------------------------------------

    @Test
    @DisplayName("the detail shows the account, its listings, its counts and what was decided about them")
    void detail() throws Exception {
        AppUser admin = admin();
        JsonNode session = signIn(admin);
        AppUser seller = person();
        jdbcTemplate.update("UPDATE app_user SET phone_e164 = '+33612345678' WHERE id = ?", seller.getId());
        UUID online = listingOf(seller, "En ligne");
        UUID sold = listingOf(seller, "Vendue");
        jdbcTemplate.update("UPDATE listing SET status = 'SOLD' WHERE id = ?", sold);
        send(session, "/api/v1/admin/listings/" + online + "/approve", Map.of()).andExpect(status().isOk());

        AppUser buyer = person();
        UUID conversation = UUID.fromString(json(send(signIn(buyer), "/api/v1/listings/" + online + "/conversation",
                Map.of("body", "Bonjour, est-elle disponible ?"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText());
        send(signIn(seller), "/api/v1/conversations/" + conversation + "/messages", Map.of("body", "Oui."))
                .andExpect(status().is2xxSuccessful());

        read(session, USERS + "/" + seller.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(seller.getEmail()))
                .andExpect(jsonPath("$.phone").value("+33612345678"))
                .andExpect(jsonPath("$.activity.listings").value(2))
                .andExpect(jsonPath("$.activity.listingsOnline").value(1))
                .andExpect(jsonPath("$.activity.conversations").value(1))
                .andExpect(jsonPath("$.activity.messagesSent").value(1))
                .andExpect(jsonPath("$.activity.activeSessions").value(1))
                .andExpect(jsonPath("$.listings.length()").value(2))
                .andExpect(jsonPath("$.listings[?(@.id == '" + online + "')].online").value(true))
                .andExpect(jsonPath("$.listings[?(@.id == '" + sold + "')].online").value(false))
                .andExpect(jsonPath("$.moderationHistory.length()").value(1))
                .andExpect(jsonPath("$.moderationHistory[0].listingTitle").value("En ligne"))
                .andExpect(jsonPath("$.moderationHistory[0].action").value("APPROVE"))
                .andExpect(jsonPath("$.moderationHistory[0].adminName").value(admin.getDisplayName()))
                .andExpect(jsonPath("$.accessTokenTtlSeconds").value(900));

        read(session, USERS + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ---- suspension ----------------------------------------------------------------------

    @Nested
    @DisplayName("suspending")
    class Suspending {

        @Test
        @DisplayName("needs a reason")
        void reasonRequired() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();

            for (Object body : List.of(Map.of(), Map.of("reason", ""), Map.of("reason", "   "))) {
                send(session, USERS + "/" + target.getId() + "/suspend", body)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
            assertThat(statusOf(target)).isEqualTo("PENDING_VERIFICATION");
            assertThat(recordedActions()).isZero();
        }

        @Test
        @DisplayName("revokes every session at once, and says how long an access token can still work")
        void revokesTokens() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();
            JsonNode laptop = signIn(target);
            JsonNode phone = signIn(target);

            suspend(session, target)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.user.activity.activeSessions").value(0))
                    .andExpect(jsonPath("$.revokedSessions").value(2))
                    .andExpect(jsonPath("$.accessTokenTtlSeconds").value(900));

            assertThat(jdbcTemplate.queryForList(
                    "SELECT revoked_reason FROM refresh_token WHERE user_id = ?", String.class, target.getId()))
                    .hasSize(2).containsOnly("ADMIN_REVOKED");
            for (JsonNode device : List.of(laptop, phone)) {
                mockMvc.perform(post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("refreshToken", device.get("refreshToken").asText()))))
                        .andExpect(status().isUnauthorized());
            }
        }

        @Test
        @DisplayName("stops the account signing in")
        void cannotSignIn() throws Exception {
            AppUser target = person();
            suspend(signIn(admin()), target).andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("email", target.getEmail(), "password", PASSWORD))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        }

        @Test
        @DisplayName("stops the account sending messages, even with an access token issued before")
        void cannotMessage() throws Exception {
            AppUser target = person();
            AppUser seller = person();
            JsonNode targetSession = signIn(target);
            UUID listing = listingOf(seller, "Audi A3");
            UUID elsewhere = listingOf(seller, "Audi A4");
            String conversation = json(send(targetSession, "/api/v1/listings/" + listing + "/conversation",
                    Map.of("body", "Bonjour")).andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();

            suspend(signIn(admin()), target).andExpect(status().isOk());

            send(targetSession, "/api/v1/conversations/" + conversation + "/messages", Map.of("body", "Encore moi"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
            send(targetSession, "/api/v1/listings/" + elsewhere + "/conversation", Map.of("body", "Bonjour"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class)).isOne();
        }

        @Test
        @DisplayName("hides the account's listings everywhere public, deletes nothing, and reactivation brings them back")
        void hidesListings() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();
            AppUser other = person();
            UUID hidden = listingOf(target, "Audi A3 du suspendu");
            UUID stays = listingOf(other, "Audi A3 de quelqu'un d'autre");
            List<Map<String, Object>> before = jdbcTemplate.queryForList("SELECT * FROM listing ORDER BY id");

            suspend(session, target).andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/listings"))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(stays.toString()));
            mockMvc.perform(get("/api/v1/listings/count")).andExpect(jsonPath("$.count").value(1));
            mockMvc.perform(get("/api/v1/listings/" + hidden)).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/listings/" + hidden + "/similar")).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/listings/" + stays)).andExpect(status().isOk());

            // The administrators still see it, and it says it is not online.
            read(session, USERS + "/" + target.getId())
                    .andExpect(jsonPath("$.listings[0].id").value(hidden.toString()))
                    .andExpect(jsonPath("$.listings[0].online").value(false));
            // Not a byte of any listing has changed, so there is nothing to put back.
            assertThat(jdbcTemplate.queryForList("SELECT * FROM listing ORDER BY id")).isEqualTo(before);

            reactivate(session, target).andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/listings/count")).andExpect(jsonPath("$.count").value(2));
            mockMvc.perform(get("/api/v1/listings/" + hidden)).andExpect(status().isOk());
            assertThat(jdbcTemplate.queryForList("SELECT * FROM listing ORDER BY id")).isEqualTo(before);
        }

        @Test
        @DisplayName("twice is refused, and so is reactivating an account that is not suspended")
        void twice() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();

            reactivate(session, target).andExpect(status().isConflict());
            suspend(session, target).andExpect(status().isOk());
            suspend(session, target).andExpect(status().isConflict());
            assertThat(recordedActions()).isOne();
        }

        @Test
        @DisplayName("reactivation restores the status the account had, without verifying anything")
        void reactivationRestores() throws Exception {
            JsonNode session = signIn(admin());
            AppUser unverified = person();
            AppUser active = createUser(uniqueEmail("active"), UserRole.PARTICULIER, UserStatus.ACTIVE);

            for (AppUser target : List.of(unverified, active)) {
                suspend(session, target).andExpect(status().isOk());
                reactivate(session, target).andExpect(status().isOk());
            }

            assertThat(statusOf(unverified)).isEqualTo("PENDING_VERIFICATION");
            assertThat(statusOf(active)).isEqualTo("ACTIVE");
            // And the account can sign in again.
            signIn(unverified);
        }
    }

    // ---- roles ---------------------------------------------------------------------------

    @Nested
    @DisplayName("changing a role")
    class ChangingARole {

        @Test
        @DisplayName("promotes and demotes, and the next token carries the new role")
        void changes() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();
            JsonNode targetSession = signIn(target);

            changeRole(session, target, UserRole.ADMIN)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.role").value("ADMIN"));

            JsonNode refreshed = json(mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", targetSession.get("refreshToken").asText()))))
                    .andExpect(status().isOk())
                    .andReturn());
            read(refreshed, USERS).andExpect(status().isOk());

            changeRole(session, target, UserRole.PARTICULIER).andExpect(status().isOk());
            assertThat(roleOf(target)).isEqualTo("PARTICULIER");
        }

        @Test
        @DisplayName("gives a new dealer a company profile, and a former one keeps theirs")
        void dealerProfile() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();

            changeRole(session, target, UserRole.PROFESSIONNEL)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.companyName").value(target.getDisplayName()));
            jdbcTemplate.update("UPDATE pro_profile SET company_name = 'Garage du Lac' WHERE user_id = ?", target.getId());

            changeRole(session, target, UserRole.PARTICULIER).andExpect(status().isOk());
            changeRole(session, target, UserRole.PROFESSIONNEL)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.companyName").value("Garage du Lac"));
        }

        @Test
        @DisplayName("refuses the role the account already has, and a role that does not exist")
        void refused() throws Exception {
            JsonNode session = signIn(admin());
            AppUser target = person();

            changeRole(session, target, UserRole.PARTICULIER).andExpect(status().isConflict());
            send(session, USERS + "/" + target.getId() + "/role", Map.of("role", "SUPERADMIN")).andExpect(status().isBadRequest());
            send(session, USERS + "/" + target.getId() + "/role", Map.of()).andExpect(status().isBadRequest());
            assertThat(recordedActions()).isZero();
        }
    }

    // ---- self-protection and the last administrator --------------------------------------

    @Nested
    @DisplayName("self-protection and the last administrator")
    class Protection {

        @Test
        @DisplayName("an administrator cannot suspend or demote themselves, even with a colleague in place")
        void self() throws Exception {
            AppUser me = admin();
            admin();
            JsonNode session = signIn(me);

            suspend(session, me)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ADMIN_SELF_ACTION"));
            for (UserRole role : List.of(UserRole.PARTICULIER, UserRole.PROFESSIONNEL)) {
                changeRole(session, me, role)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ADMIN_SELF_ACTION"));
            }
            assertThat(statusOf(me)).isEqualTo("ACTIVE");
            assertThat(roleOf(me)).isEqualTo("ADMIN");
            assertThat(recordedActions()).isZero();
        }

        private void assertLastAdmin(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
            assertThatThrownBy(action)
                    .isInstanceOfSatisfying(com.autox.api.error.ApiException.class,
                            refused -> assertThat(refused.getCode()).isEqualTo(com.autox.api.error.ErrorCode.LAST_ADMIN));
        }

        @Test
        @DisplayName("the last administrator cannot be suspended or demoted, whoever asks")
        void lastAdmin() {
            // At the service, past the check on the way in: someone whose rights ended after it,
            // the only way anyone but the last administrator themselves can get this far.
            AppUser last = admin();
            AppUser former = admin();
            jdbcTemplate.update("UPDATE app_user SET role = 'PARTICULIER' WHERE id = ?", former.getId());

            assertLastAdmin(() -> service.suspend(former.getId(), last.getId(), "Motif"));
            assertLastAdmin(() -> service.changeRole(former.getId(), last.getId(), UserRole.PARTICULIER, null));
            assertThat(statusOf(last)).isEqualTo("ACTIVE");
            assertThat(roleOf(last)).isEqualTo("ADMIN");
            assertThat(recordedActions()).isZero();
        }

        @Test
        @DisplayName("a suspended administrator does not count as one left")
        void suspendedDoesNotCount() {
            AppUser last = admin();
            createUser(uniqueEmail("admin"), UserRole.ADMIN, UserStatus.SUSPENDED);
            AppUser former = admin();
            jdbcTemplate.update("UPDATE app_user SET role = 'PARTICULIER' WHERE id = ?", former.getId());

            assertLastAdmin(() -> service.suspend(former.getId(), last.getId(), "Motif"));
        }

        @Test
        @DisplayName("rights taken away after the check on the way in are caught under the lock")
        void rightsEndedOnTheWay() {
            AppUser former = admin();
            admin();
            AppUser target = person();
            jdbcTemplate.update("UPDATE app_user SET role = 'PARTICULIER' WHERE id = ?", former.getId());

            assertThatThrownBy(() -> service.suspend(former.getId(), target.getId(), "Motif"))
                    .isInstanceOfSatisfying(com.autox.api.error.ApiException.class,
                            refused -> assertThat(refused.getCode()).isEqualTo(com.autox.api.error.ErrorCode.FORBIDDEN));
            assertThat(statusOf(target)).isEqualTo("PENDING_VERIFICATION");
        }

        @Test
        @DisplayName("with two administrators one can remove the other, and a suspended one can be demoted")
        void withTwo() throws Exception {
            AppUser one = admin();
            AppUser two = admin();
            JsonNode session = signIn(one);

            suspend(session, two).andExpect(status().isOk());
            changeRole(session, two, UserRole.PARTICULIER).andExpect(status().isOk());
            assertThat(roleOf(two)).isEqualTo("PARTICULIER");
        }

        @Test
        @DisplayName("two administrators demoting each other at the same moment leave one")
        void concurrentDemotion() throws Exception {
            for (int attempt = 0; attempt < 5; attempt++) {
                cleaner.truncateTransactionalTables();
                AppUser one = admin();
                AppUser two = admin();
                JsonNode oneSession = signIn(one);
                JsonNode twoSession = signIn(two);
                CountDownLatch start = new CountDownLatch(1);

                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Callable<Integer> first = () -> {
                        start.await();
                        return changeRole(oneSession, two, UserRole.PARTICULIER).andReturn().getResponse().getStatus();
                    };
                    Callable<Integer> second = () -> {
                        start.await();
                        return changeRole(twoSession, one, UserRole.PARTICULIER).andReturn().getResponse().getStatus();
                    };
                    Future<Integer> a = pool.submit(first);
                    Future<Integer> b = pool.submit(second);
                    start.countDown();

                    // The second is refused under the lock (409), or on the way in if the first has
                    // already finished (403). Either way, exactly one goes through.
                    assertThat(List.of(a.get(), b.get())).containsOnlyOnce(200).containsAnyOf(403, 409);
                } finally {
                    pool.shutdownNow();
                }
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM app_user WHERE role = 'ADMIN'", Integer.class)).isOne();
                assertThat(recordedActions()).isOne();
            }
        }
    }

    // ---- the record ----------------------------------------------------------------------

    @Nested
    @DisplayName("the record of each action")
    class TheRecord {

        @Test
        @DisplayName("says who did what to whom, why and when")
        void recorded() throws Exception {
            AppUser admin = admin();
            JsonNode session = signIn(admin);
            AppUser target = person();

            suspend(session, target).andExpect(status().isOk());
            send(session, USERS + "/" + target.getId() + "/reactivate", Map.of("note", "Erreur de ma part")).andExpect(status().isOk());
            send(session, USERS + "/" + target.getId() + "/role", Map.of("role", "PROFESSIONNEL", "note", "Justificatif reçu")).andExpect(status().isOk());

            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT admin_id, admin_email, target_user_id, target_email, action, reason, previous_value,
                           new_value, created_at
                      FROM admin_user_action ORDER BY id""");
            assertThat(rows).hasSize(3);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("admin_id")).isEqualTo(admin.getId());
                assertThat(row.get("admin_email")).isEqualTo(admin.getEmail());
                assertThat(row.get("target_user_id")).isEqualTo(target.getId());
                assertThat(row.get("target_email")).isEqualTo(target.getEmail());
                assertThat(row.get("created_at")).isNotNull();
            });
            assertThat(rows.get(0)).containsEntry("action", "SUSPEND")
                    .containsEntry("reason", "Annonces frauduleuses")
                    .containsEntry("previous_value", "PENDING_VERIFICATION")
                    .containsEntry("new_value", "SUSPENDED");
            assertThat(rows.get(1)).containsEntry("action", "REACTIVATE")
                    .containsEntry("reason", "Erreur de ma part")
                    .containsEntry("previous_value", "SUSPENDED")
                    .containsEntry("new_value", "PENDING_VERIFICATION");
            assertThat(rows.get(2)).containsEntry("action", "CHANGE_ROLE")
                    .containsEntry("reason", "Justificatif reçu")
                    .containsEntry("previous_value", "PARTICULIER")
                    .containsEntry("new_value", "PROFESSIONNEL");

            // The account's page shows them, newest first.
            read(session, USERS + "/" + target.getId())
                    .andExpect(jsonPath("$.actions.length()").value(3))
                    .andExpect(jsonPath("$.actions[0].action").value("CHANGE_ROLE"))
                    .andExpect(jsonPath("$.actions[2].reason").value("Annonces frauduleuses"))
                    .andExpect(jsonPath("$.actions[2].adminEmail").value(admin.getEmail()));
        }

        @Test
        @DisplayName("cannot be changed or deleted, and a reasonless suspension cannot be written")
        void appendOnly() throws Exception {
            AppUser target = person();
            suspend(signIn(admin()), target).andExpect(status().isOk());

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE admin_user_action SET reason = 'Autre'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM admin_user_action"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO admin_user_action (id, admin_id, admin_email, target_user_id, target_email, action,
                                                   previous_value, new_value)
                    VALUES (gen_random_uuid(), gen_random_uuid(), 'a', gen_random_uuid(), 'b', 'SUSPEND', 'ACTIVE', 'SUSPENDED')"""))
                    .hasMessageContaining("ck_admin_user_action_reason");
        }
    }
}
