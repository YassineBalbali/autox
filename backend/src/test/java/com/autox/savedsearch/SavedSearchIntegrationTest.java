package com.autox.savedsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.catalog.VehicleModel;
import com.autox.catalog.VehicleModelRepository;
import com.autox.mail.EmailMessage;
import com.autox.mail.OutboxPoller;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.support.RecordingEmailSender;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Saved searches and their daily e-mail, against a real PostgreSQL with real commits. */
@TestPropertySource(properties = "autox.mail.provider = recording")
// Imported rather than found by being nested here: @Nested test classes do not look for it.
@org.springframework.context.annotation.Import(SavedSearchIntegrationTest.RecordingMailConfiguration.class)
class SavedSearchIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCHES = "/api/v1/saved-searches";
    private static final String NO_REPLY = "Cet e-mail est envoyé automatiquement, merci de ne pas y répondre.";

    private static final Map<String, Object> LOA = Map.of(
            "offerType", "LEASING", "type", "LOA", "monthlyPayment", 25_000L, "firstPayment", 200_000L,
            "durationMonths", 37, "mileagePerYear", 15_000, "optionPrice", 1_000_000L);
    private static final Map<String, Object> LLD = Map.of(
            "offerType", "LEASING", "type", "LLD", "monthlyPayment", 40_000L, "firstPayment", 100_000L,
            "durationMonths", 36, "mileagePerYear", 10_000);
    private static final Map<String, Object> DAMAGE = Map.of(
            "damaged", true, "types", List.of("BODYWORK"), "running", false, "description", "Choc avant.");

    @TestConfiguration
    static class RecordingMailConfiguration {

        @Bean
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired RecordingEmailSender emails;
    @Autowired OutboxPoller poller;
    @Autowired AlertDigest digest;
    @Autowired AlertDigestJob job;
    @Autowired UnsubscribeToken tokens;
    @Autowired VehicleMakeRepository makes;
    @Autowired VehicleModelRepository models;

    private JsonNode dealer;
    private JsonNode searcher;
    private String searcherEmail;

    @BeforeEach
    void setUp() throws Exception {
        emails.clear();
        dealer = register(uniqueEmail("dealer"), UserRole.PROFESSIONNEL);
        searcherEmail = uniqueEmail("searcher");
        searcher = verified(searcherEmail);
    }

    // ---- matching ------------------------------------------------------------------------

    @Nested
    @DisplayName("an alert matches what the results page shows")
    class Matching {

        @Test
        @DisplayName("sales: filters on fuel, price, year, gearbox, make, text and place")
        void sale() throws Exception {
            String diesel = publish(Map.of("fuelType", "DIESEL", "priceAmount", 1_000_000L, "makeText", "Renault", "modelText", "Clio", "versionText", "dCi", "title", "Renault Clio dCi"));
            publish(Map.of("fuelType", "PETROL", "priceAmount", 1_000_000L));
            publish(Map.of("fuelType", "DIESEL", "priceAmount", 3_000_000L));
            publish(Map.of("offer", LOA));
            publish(Map.of("damage", DAMAGE));

            assertThat(sameAsResultsPage(Map.of("fuelType", List.of("DIESEL"), "priceMax", 2_000_000L, "currency", "EUR")))
                    .containsExactly(diesel);
            assertThat(sameAsResultsPage(Map.of())).hasSize(3);
            assertThat(sameAsResultsPage(Map.of("make", "Renault"))).containsExactly(diesel);
            assertThat(sameAsResultsPage(Map.of("q", "clio dci"))).containsExactly(diesel);
            assertThat(sameAsResultsPage(Map.of("yearFrom", 2019, "gearbox", List.of("AUTOMATIC"), "location", "Paris")))
                    .hasSize(3);
            assertThat(sameAsResultsPage(Map.of("yearFrom", 2030))).isEmpty();
        }

        @Test
        @DisplayName("leasing: only offers, with their type, monthly payment and mileage allowance")
        void leasing() throws Exception {
            String loa = publish(Map.of("offer", LOA));
            String lld = publish(Map.of("offer", LLD));
            publish(Map.of());

            assertThat(sameAsResultsPage(Map.of("offer", "LEASING"))).containsExactlyInAnyOrder(loa, lld);
            assertThat(sameAsResultsPage(Map.of("offer", "LEASING", "leasingType", List.of("LOA")))).containsExactly(loa);
            assertThat(sameAsResultsPage(Map.of("offer", "LEASING", "monthlyMax", 30_000L))).containsExactly(loa);
            assertThat(sameAsResultsPage(Map.of("offer", "LEASING", "durationMin", 37))).containsExactly(loa);
            assertThat(sameAsResultsPage(Map.of("offer", "LEASING", "mileageAllowanceMin", 12_000))).containsExactly(loa);
        }

        @Test
        @DisplayName("damage: out unless asked for, alone, or with the rest")
        void damaged() throws Exception {
            String damaged = publish(Map.of("damage", DAMAGE));
            String sound = publish(Map.of());

            assertThat(sameAsResultsPage(Map.of())).containsExactly(sound);
            assertThat(sameAsResultsPage(Map.of("damage", "UNDAMAGED"))).containsExactly(sound);
            assertThat(sameAsResultsPage(Map.of("damage", "DAMAGED_ONLY"))).containsExactly(damaged);
            assertThat(sameAsResultsPage(Map.of("damage", "ALL"))).containsExactlyInAnyOrder(damaged, sound);
        }

        @Test
        @DisplayName("a listing taken offline before the e-mail goes is not in it, as it is not in the results")
        void onlyVisibleListings() throws Exception {
            String paused = publish(Map.of());
            String online = publish(Map.of());
            mockMvc.perform(post("/api/v1/listings/" + paused + "/unpublish").header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                    .andExpect(status().isOk());

            assertThat(sameAsResultsPage(Map.of())).containsExactly(online);
        }

        /**
         * Saves the search, sends its digest, and compares what was sent with what the public
         * search answers for the same criteria.
         */
        private List<String> sameAsResultsPage(Map<String, Object> criteria) throws Exception {
            String account = uniqueEmail("matcher");
            JsonNode session = verified(account);
            String id = id(save(session, "Comparaison", criteria).andExpect(status().isCreated()));
            backdate(id);

            digest.digestFor(userId(session), false);

            List<String> sent = jdbcTemplate.queryForList(
                    "SELECT listing_id::text FROM saved_search_sent WHERE saved_search_id = ?::uuid", String.class, id);
            List<String> results = new ArrayList<>();
            json(publicSearch(criteria).andExpect(status().isOk()).andReturn()).get("items")
                    .forEach(item -> results.add(item.get("id").asText()));
            assertThat(sent).containsExactlyInAnyOrderElementsOf(results);
            return results;
        }

        private ResultActions publicSearch(Map<String, Object> criteria) throws Exception {
            MockHttpServletRequestBuilder request = get("/api/v1/listings");
            criteria.forEach((name, value) -> {
                if (value instanceof List<?> values) {
                    values.forEach(each -> request.param(name, each.toString()));
                } else {
                    request.param(name, value.toString());
                }
            });
            return mockMvc.perform(request);
        }
    }

    // ---- the digest ----------------------------------------------------------------------

    @Nested
    @DisplayName("the daily e-mail")
    class Digest {

        @Test
        @DisplayName("only what was published since the last one")
        void onlySinceTheLastDigest() throws Exception {
            publish(Map.of("title", "Publiée avant"));
            String id = id(save(searcher, "Tout", Map.of()));
            jdbcTemplate.update("UPDATE saved_search SET created_at = now() + interval '1 second', "
                    + "last_digest_at = now() + interval '1 second' WHERE id = ?::uuid", id);

            assertThat(digest.digestFor(userId(searcher), true)).isFalse();

            assertThat(outboxRows()).isZero();
        }

        @Test
        @DisplayName("nothing new, no e-mail")
        void nothingNew() throws Exception {
            save(searcher, "Rien de neuf", Map.of());

            job.runNow();
            poller.pollNow();

            assertThat(outboxRows()).isZero();
            assertThat(emails.sent()).isEmpty();
        }

        @Test
        @DisplayName("a listing is never sent twice for a search, even inside the look-back")
        void noDuplicates() throws Exception {
            String id = id(save(searcher, "Tout", Map.of()));
            backdate(id);
            String listing = publish(Map.of());

            assertThat(digest.digestFor(userId(searcher), false)).isTrue();
            // As if the last digest had been a minute ago: the listing is inside the look-back.
            jdbcTemplate.update("UPDATE saved_search SET last_digest_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
            assertThat(digest.digestFor(userId(searcher), false)).isFalse();

            assertThat(sentFor(id)).containsExactly(listing);
            assertThat(outboxRows()).isOne();
        }

        @Test
        @DisplayName("but two searches can each be told about the same listing")
        void perSearch() throws Exception {
            String one = id(save(searcher, "Une", Map.of()));
            String other = id(save(searcher, "Autre", Map.of("fuelType", List.of("DIESEL"))));
            backdate(one);
            backdate(other);
            String listing = publish(Map.of());

            digest.digestFor(userId(searcher), false);

            assertThat(sentFor(one)).containsExactly(listing);
            assertThat(sentFor(other)).containsExactly(listing);
        }

        @Test
        @DisplayName("the searcher's own listings are left out")
        void notOwnListings() throws Exception {
            JsonNode seller = verifiedDealer();
            String id = id(save(seller, "Les miennes aussi ?", Map.of()));
            backdate(id);
            String mine = publishAs(seller, Map.of());
            String other = publish(Map.of());

            digest.digestFor(userId(seller), false);

            assertThat(sentFor(id)).containsExactly(other).doesNotContain(mine);
        }

        @Test
        @DisplayName("one e-mail per person per day, grouping their searches, and what came after goes tomorrow")
        void oncePerDay() throws Exception {
            backdate(id(save(searcher, "Mes diesels", Map.of("fuelType", List.of("DIESEL")))));
            backdate(id(save(searcher, "Tout", Map.of())));
            publish(Map.of("fuelType", "DIESEL"));

            job.runNow();
            poller.pollNow();

            assertThat(emails.sent()).singleElement().satisfies(email -> {
                assertThat(email.to()).isEqualTo(searcherEmail);
                assertThat(email.subject()).isEqualTo("2 nouvelles annonces pour vos recherches AutoX");
                assertThat(email.textBody()).contains("Mes diesels").contains("Tout");
            });

            // A later run the same day sends nothing, and loses nothing.
            String later = publish(Map.of());
            job.runNow();
            assertThat(outboxRows()).isOne();

            jdbcTemplate.update("UPDATE outbox_email SET created_at = created_at - interval '1 day'");
            job.runNow();
            assertThat(outboxRows()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_search_sent WHERE listing_id = ?::uuid",
                    Long.class, later)).isPositive();
        }

        @Test
        @DisplayName("at most ten listings a search, with the number new and a link to all of them")
        void tenAtMost() throws Exception {
            String id = id(save(searcher, "Tout", Map.of(), Map.of("carburant", "diesel", "q", "golf gti")));
            backdate(id);
            for (int i = 0; i < 12; i++) {
                publish(Map.of());
            }

            digest.digestFor(userId(searcher), false);
            EmailMessage email = sendAll().getFirst();

            assertThat(sentFor(id)).hasSize(10);
            assertThat(email.subject()).startsWith("12 nouvelles annonces");
            assertThat(countOf(email.textBody(), "/annonces/")).isEqualTo(10);
            assertThat(countOf(email.htmlBody(), "/annonces/")).isEqualTo(10);
            // The order is whatever the stored JSON gives, and means nothing to the page.
            assertThat(email.textBody()).containsAnyOf(
                    "https://autox.test/recherche?carburant=diesel&q=golf%20gti",
                    "https://autox.test/recherche?q=golf%20gti&carburant=diesel");
        }

        @Test
        @DisplayName("every e-mail has a link to unsubscribe per search, Mes recherches and the footer")
        void links() throws Exception {
            String one = id(save(searcher, "Une", Map.of()));
            String other = id(save(searcher, "Autre", Map.of()));
            backdate(one);
            backdate(other);
            publish(Map.of());

            digest.digestFor(userId(searcher), false);
            EmailMessage email = sendAll().getFirst();

            for (String body : List.of(email.textBody(), email.htmlBody())) {
                assertThat(body).contains("https://autox.test/alertes/desabonnement?token=" + tokens.sign(UUID.fromString(one)));
                assertThat(body).contains("https://autox.test/alertes/desabonnement?token=" + tokens.sign(UUID.fromString(other)));
                assertThat(body).contains("https://autox.test/mes-recherches").contains(NO_REPLY);
            }
            assertThat(email.textBody()).endsWith(NO_REPLY);
            assertThat(email.htmlBody()).endsWith(NO_REPLY + "</p>\n</div></body></html>");
        }

        @Test
        @DisplayName("the listing is named as on the result cards, and what users typed is escaped")
        void labelsAndEscaping() throws Exception {
            backdate(id(save(searcher, "<b>Ma recherche</b>", Map.of())));
            publish(Map.of("title", "Audi Audi A3 très propre", "makeText", "Audi", "modelText", "<A3>", "versionText", "35 TDI"));

            digest.digestFor(userId(searcher), false);
            EmailMessage email = sendAll().getFirst();

            assertThat(email.textBody()).contains("Audi <A3> 35 TDI").doesNotContain("Audi Audi");
            assertThat(email.htmlBody()).contains("Audi &lt;A3&gt; 35 TDI").doesNotContain("<A3>");
            assertThat(email.htmlBody()).contains("&lt;b&gt;Ma recherche&lt;/b&gt;").doesNotContain("<b>Ma");
        }

        @Test
        @DisplayName("monthly payments carry the credit mention; a digest of sales does not")
        void creditMention() throws Exception {
            String mention = "Un crédit vous engage et doit être remboursé.";
            backdate(id(save(searcher, "Louer", Map.of("offer", "LEASING"))));
            JsonNode buyer = verified(uniqueEmail("sales"));
            backdate(id(save(buyer, "Acheter", Map.of())));
            publish(Map.of("offer", LOA));
            publish(Map.of());

            digest.digestFor(userId(searcher), false);
            EmailMessage leasing = sendAll().getLast();
            digest.digestFor(userId(buyer), false);
            EmailMessage sale = sendAll().getLast();

            assertThat(leasing.textBody()).contains(mention).contains("à partir de 250");
            assertThat(leasing.htmlBody()).contains(mention);
            assertThat(sale.textBody()).doesNotContain(mention).doesNotContain("/mois");
        }

        @Test
        @DisplayName("a paused search, a suspended account and an unverified one get nothing")
        void whoGetsNothing() throws Exception {
            String paused = id(save(searcher, "En pause", Map.of()));
            backdate(paused);
            update(searcher, paused, Map.of("paused", true)).andExpect(status().isOk());
            String email = uniqueEmail("suspended");
            JsonNode suspended = verified(email);
            backdate(id(save(suspended, "Suspendu", Map.of())));
            jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE email = ?", email);
            String lapsed = uniqueEmail("lapsed");
            JsonNode unverified = verified(lapsed);
            backdate(id(save(unverified, "Non vérifié", Map.of())));
            jdbcTemplate.update("UPDATE app_user SET email_verified_at = NULL WHERE email = ?", lapsed);
            publish(Map.of());

            job.runNow();

            assertThat(outboxRows()).isZero();
        }

        @Test
        @DisplayName("a search that cannot be read costs that search alone")
        void oneAccountsFailureSparesTheOthers() throws Exception {
            String broken = id(save(searcher, "Illisible", Map.of()));
            jdbcTemplate.update("UPDATE saved_search SET criteria_version = 99 WHERE id = ?::uuid", broken);
            String readable = id(save(searcher, "Lisible", Map.of()));
            backdate(readable);
            JsonNode other = verified(uniqueEmail("other"));
            backdate(id(save(other, "Autre compte", Map.of())));
            publish(Map.of());

            job.runNow();

            assertThat(outboxRows()).isEqualTo(2);
            assertThat(sentFor(broken)).isEmpty();
            assertThat(sentFor(readable)).hasSize(1);
        }

        @Test
        @DisplayName("the job runs on Paris time, whatever the server's zone")
        void parisTime() throws Exception {
            Scheduled scheduled = AlertDigestJob.class.getMethod("run").getAnnotation(Scheduled.class);

            assertThat(scheduled.zone()).isEqualTo("Europe/Paris");
            assertThat(scheduled.cron()).isEqualTo("${autox.scheduling.alert-digest-cron}");
        }
    }

    // ---- saving ---------------------------------------------------------------------------

    @Nested
    @DisplayName("saving a search")
    class Saving {

        @Test
        @DisplayName("needs an account")
        void anonymous() throws Exception {
            mockMvc.perform(post(SEARCHES).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body("Alerte", Map.of(), Map.of()))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("needs a verified address: a new account's, and an older account's that was never asked")
        void unverifiedBlocked() throws Exception {
            JsonNode fresh = registerUnverified(uniqueEmail("fresh"), UserRole.PARTICULIER);
            JsonNode older = register(uniqueEmail("older"), UserRole.PARTICULIER);

            for (JsonNode account : List.of(fresh, older)) {
                save(account, "Alerte", Map.of()).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
            }
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_search", Long.class)).isZero();
        }

        @Test
        @DisplayName("ten at most, paused ones included, and deleting one makes room")
        void tenSearches() throws Exception {
            String first = null;
            for (int i = 0; i < 10; i++) {
                String id = id(save(searcher, "Alerte " + i, Map.of()).andExpect(status().isCreated()));
                first = first == null ? id : first;
            }
            update(searcher, first, Map.of("paused", true)).andExpect(status().isOk());

            save(searcher, "De trop", Map.of()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SAVED_SEARCH_LIMIT_REACHED"));

            mockMvc.perform(delete(SEARCHES + "/" + first).header(HttpHeaders.AUTHORIZATION, bearer(searcher)))
                    .andExpect(status().isNoContent());
            save(searcher, "Maintenant oui", Map.of()).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("ten at most holds when saves arrive together")
        void tenSearchesConcurrently() throws Exception {
            for (int i = 0; i < 9; i++) {
                save(searcher, "Alerte " + i, Map.of()).andExpect(status().isCreated());
            }
            int racers = 4;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger created = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        if (save(searcher, "Course", Map.of()).andReturn().getResponse().getStatus() == 201) {
                            created.incrementAndGet();
                        }
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }

            assertThat(created).hasValue(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_search", Long.class)).isEqualTo(10);
        }

        @Test
        @DisplayName("a query string that is not one is refused, and a blank name")
        void validation() throws Exception {
            save(searcher, "Alerte", Map.of(), Map.of("https://piege.example/?a", "1")).andExpect(status().isBadRequest());
            save(searcher, "   ", Map.of()).andExpect(status().isBadRequest());
            save(searcher, "x".repeat(81), Map.of()).andExpect(status().isBadRequest());
            save(searcher, "Alerte", Map.of("yearFrom", 1000)).andExpect(status().isBadRequest());
        }
    }

    // ---- Mes recherches -------------------------------------------------------------------

    @Nested
    @DisplayName("Mes recherches")
    class MySearches {

        @Test
        @DisplayName("lists them, renames, pauses and resumes")
        void manage() throws Exception {
            String id = id(save(searcher, "Avant", Map.of(), Map.of("carburant", "diesel")));

            update(searcher, id, Map.of("name", " Après ")).andExpect(jsonPath("$.name").value("Après"));
            update(searcher, id, Map.of("paused", true)).andExpect(jsonPath("$.paused").value(true));

            list(searcher).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(id))
                    .andExpect(jsonPath("$[0].name").value("Après"))
                    .andExpect(jsonPath("$[0].paused").value(true))
                    .andExpect(jsonPath("$[0].readable").value(true))
                    .andExpect(jsonPath("$[0].params.carburant").value("diesel"));

            update(searcher, id, Map.of("paused", false)).andExpect(jsonPath("$.paused").value(false));
        }

        @Test
        @DisplayName("resuming does not send what was published during the pause")
        void resumeStartsFromNow() throws Exception {
            String id = id(save(searcher, "Tout", Map.of()));
            backdate(id);
            update(searcher, id, Map.of("paused", true));
            publish(Map.of());
            update(searcher, id, Map.of("paused", false));
            // The look-back is about listings still committing, not a way round this.
            jdbcTemplate.update("UPDATE listing SET published_at = now() - interval '1 hour'");

            assertThat(digest.digestFor(userId(searcher), false)).isFalse();
        }

        @Test
        @DisplayName("counts what is new since the last visit, not the searcher's own, and a visit starts again")
        void newSinceLastVisit() throws Exception {
            JsonNode seller = verifiedDealer();
            String id = id(save(seller, "Tout", Map.of()));
            jdbcTemplate.update("UPDATE saved_search SET last_visited_at = now() - interval '1 hour' WHERE id = ?::uuid", id);
            publish(Map.of());
            publish(Map.of());
            publishAs(seller, Map.of());

            list(seller).andExpect(jsonPath("$[0].newCount").value(2))
                    .andExpect(jsonPath("$[0].newCountCapped").value(false));

            mockMvc.perform(post(SEARCHES + "/" + id + "/visit").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                    .andExpect(status().isNoContent());

            list(seller).andExpect(jsonPath("$[0].newCount").value(0));
        }

        @Test
        @DisplayName("someone else's search answers 404, for everything")
        void notYours() throws Exception {
            String id = id(save(searcher, "À moi", Map.of()));
            JsonNode stranger = verified(uniqueEmail("stranger"));
            String authorization = bearer(stranger);

            update(stranger, id, Map.of("name", "À moi maintenant")).andExpect(status().isNotFound());
            mockMvc.perform(delete(SEARCHES + "/" + id).header(HttpHeaders.AUTHORIZATION, authorization))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post(SEARCHES + "/" + id + "/visit").header(HttpHeaders.AUTHORIZATION, authorization))
                    .andExpect(status().isNotFound());
            list(stranger).andExpect(jsonPath("$.length()").value(0));
        }
    }

    // ---- when the catalogue changes -------------------------------------------------------

    @Nested
    @DisplayName("a make or a model that has gone")
    class CatalogueChanges {

        private VehicleMake make;
        private VehicleModel model;

        @BeforeEach
        void catalogue() {
            String slug = "marque-" + UUID.randomUUID().toString().substring(0, 8);
            make = makes.saveAndFlush(VehicleMake.of(slug, "Marque Disparue"));
            model = models.saveAndFlush(VehicleModel.of(make, slug + "-m", "Modèle Disparu"));
        }

        @Test
        @DisplayName("a make: the search keeps working without it, its link too, and the page says so")
        void makeGone() throws Exception {
            String id = id(save(searcher, "Disparue", Map.of("makeId", make.getId(), "modelId", model.getId(), "fuelType", List.of("DIESEL")),
                    Map.of("marque", make.getSlug(), "modele", model.getSlug(), "carburant", "diesel")));
            backdate(id);
            jdbcTemplate.update("DELETE FROM vehicle_model WHERE id = ?", model.getId());
            jdbcTemplate.update("DELETE FROM vehicle_make WHERE id = ?", make.getId());
            String diesel = publish(Map.of("fuelType", "DIESEL"));
            publish(Map.of("fuelType", "PETROL"));

            list(searcher).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].missingMake").value("Marque Disparue"))
                    .andExpect(jsonPath("$[0].readable").value(true))
                    .andExpect(jsonPath("$[0].params.carburant").value("diesel"))
                    .andExpect(jsonPath("$[0].params.marque").doesNotExist())
                    .andExpect(jsonPath("$[0].params.modele").doesNotExist());

            digest.digestFor(userId(searcher), false);

            assertThat(sentFor(id)).containsExactly(diesel);
            assertThat(sendAll().getFirst().textBody()).contains("https://autox.test/recherche?carburant=diesel");
        }

        @Test
        @DisplayName("a model alone: the make stands")
        void modelGone() throws Exception {
            String id = id(save(searcher, "Disparu", Map.of("makeId", make.getId(), "modelId", model.getId()),
                    Map.of("marque", make.getSlug(), "modele", model.getSlug())));
            backdate(id);
            jdbcTemplate.update("DELETE FROM vehicle_model WHERE id = ?", model.getId());
            String kept = publish(Map.of());
            jdbcTemplate.update("UPDATE listing SET make_id = ?, make_text = NULL WHERE id = ?::uuid", make.getId(), kept);
            publish(Map.of());

            list(searcher).andExpect(jsonPath("$[0].missingModel").value("Modèle Disparu"))
                    .andExpect(jsonPath("$[0].missingMake").doesNotExist())
                    .andExpect(jsonPath("$[0].params.marque").value(make.getSlug()))
                    .andExpect(jsonPath("$[0].params.modele").doesNotExist());

            digest.digestFor(userId(searcher), false);

            assertThat(sentFor(id)).containsExactly(kept);
        }

        @Test
        @DisplayName("filters in a later version than this code can read are reported, and send nothing")
        void unreadable() throws Exception {
            String id = id(save(searcher, "Du futur", Map.of()));
            backdate(id);
            jdbcTemplate.update("UPDATE saved_search SET criteria_version = 99 WHERE id = ?::uuid", id);
            publish(Map.of());

            list(searcher).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].readable").value(false))
                    .andExpect(jsonPath("$[0].newCount").value(0));

            assertThat(digest.digestFor(userId(searcher), false)).isFalse();
        }

        @Test
        @DisplayName("a value that no longer exists, or a field, costs that alone")
        void laterFilterChanges() throws Exception {
            String id = id(save(searcher, "Ancienne", Map.of()));
            backdate(id);
            jdbcTemplate.update("""
                    UPDATE saved_search
                       SET criteria = '{"fuelType":["DIESEL","VAPEUR"],"champSupprime":"x"}'::jsonb
                     WHERE id = ?::uuid
                    """, id);
            String diesel = publish(Map.of("fuelType", "DIESEL"));
            publish(Map.of("fuelType", "PETROL"));

            list(searcher).andExpect(jsonPath("$[0].readable").value(true));
            digest.digestFor(userId(searcher), false);

            assertThat(sentFor(id)).containsExactly(diesel);
        }
    }

    // ---- unsubscribing --------------------------------------------------------------------

    @Nested
    @DisplayName("unsubscribing from an e-mail")
    class Unsubscribing {

        @Test
        @DisplayName("the link in the e-mail pauses that search alone, with no sign-in, and again is no error")
        void fromTheEmail() throws Exception {
            String one = id(save(searcher, "Une", Map.of()));
            String other = id(save(searcher, "Autre", Map.of()));
            backdate(one);
            backdate(other);
            publish(Map.of());
            digest.digestFor(userId(searcher), false);
            String body = sendAll().getFirst().textBody();
            Matcher link = Pattern.compile("Ne plus recevoir cette alerte : \\S+token=(\\S+)").matcher(body);
            assertThat(link.find()).isTrue();

            unsubscribe(link.group(1)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Une"));
            unsubscribe(link.group(1)).andExpect(status().isOk());

            assertThat(pausedAt(one)).isNotNull();
            assertThat(pausedAt(other)).isNull();
        }

        @Test
        @DisplayName("a token not signed here, or altered, is refused and changes nothing")
        void forged() throws Exception {
            String id = id(save(searcher, "Une", Map.of()));
            String good = tokens.sign(UUID.fromString(id));
            String otherId = tokens.sign(UUID.randomUUID());
            String mixed = good.substring(0, good.indexOf('.')) + otherId.substring(otherId.indexOf('.'));

            for (String bad : List.of("n-importe-quoi", id, mixed, good + "x", good.substring(1))) {
                unsubscribe(bad).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_UNSUBSCRIBE_TOKEN"));
            }
            assertThat(pausedAt(id)).isNull();
        }

        @Test
        @DisplayName("a search deleted since is not an error")
        void deletedSince() throws Exception {
            String id = id(save(searcher, "Une", Map.of()));
            String token = tokens.sign(UUID.fromString(id));
            jdbcTemplate.update("DELETE FROM saved_search WHERE id = ?::uuid", id);

            unsubscribe(token).andExpect(status().isOk()).andExpect(jsonPath("$.name").doesNotExist());
        }

        private ResultActions unsubscribe(String token) throws Exception {
            return mockMvc.perform(post(SEARCHES + "/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("token", token))));
        }

        private Object pausedAt(String id) {
            return jdbcTemplate.queryForObject("SELECT paused_at FROM saved_search WHERE id = ?::uuid", Object.class, id);
        }
    }

    // ---- administration -------------------------------------------------------------------

    @Nested
    @DisplayName("administration")
    class Administration {

        @Test
        @DisplayName("an administrator can send themselves their digest now, repeatedly, and nobody else gets one")
        void sendMine() throws Exception {
            JsonNode admin = verifiedAdmin();
            backdate(id(save(admin, "Test", Map.of())));
            backdate(id(save(searcher, "Quelqu'un d'autre", Map.of())));
            publish(Map.of());

            send(admin).andExpect(status().isOk()).andExpect(jsonPath("$.written").value(true));
            assertThat(outboxRows()).isOne();
            send(admin).andExpect(jsonPath("$.written").value(false));

            publish(Map.of());
            send(admin).andExpect(jsonPath("$.written").value(true));
            assertThat(outboxRows()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_email WHERE recipient = ?", Long.class, searcherEmail))
                    .isZero();
        }

        @Test
        @DisplayName("nobody else can")
        void onlyAdministrators() throws Exception {
            send(searcher).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("the dashboard counts active searches, and alert e-mails sent over 7 and 30 days")
        void dashboard() throws Exception {
            JsonNode admin = verifiedAdmin();
            save(searcher, "Active", Map.of());
            update(searcher, id(save(searcher, "En pause", Map.of())), Map.of("paused", true));
            outboxEmail("SAVED_SEARCH_DIGEST", "SENT", "now()");
            outboxEmail("SAVED_SEARCH_DIGEST", "SENT", "now() - interval '10 days'");
            outboxEmail("SAVED_SEARCH_DIGEST", "SENT", "now() - interval '40 days'");
            outboxEmail("SAVED_SEARCH_DIGEST", "FAILED", "now()");
            outboxEmail("NEW_MESSAGE", "SENT", "now()");

            JsonNode stats = json(mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isOk()).andReturn());

            assertThat(stats.get("activeSavedSearches").asLong()).isOne();
            assertThat(stats.at("/emailsLast7Days/alertsSent").asLong()).isEqualTo(1);
            assertThat(stats.at("/emailsLast30Days/alertsSent").asLong()).isEqualTo(2);
            assertThat(stats.at("/emailsLast7Days/sent").asLong()).isEqualTo(2);
        }

        private ResultActions send(JsonNode who) throws Exception {
            return mockMvc.perform(post("/api/v1/admin/alerts/send-mine").header(HttpHeaders.AUTHORIZATION, bearer(who)));
        }

        private void outboxEmail(String type, String state, String createdAt) {
            jdbcTemplate.update("""
                    INSERT INTO outbox_email (id, recipient, subject, email_type, status, sent_at, created_at)
                    VALUES (?, 'r@autox.test', 'Objet', ?, ?, %s, %s)
                    """.formatted("SENT".equals(state) ? "now()" : "NULL", createdAt), UUID.randomUUID(), type, state);
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private JsonNode verified(String email) throws Exception {
        JsonNode session = register(email, UserRole.PARTICULIER);
        jdbcTemplate.update("UPDATE app_user SET email_verified_at = now(), status = 'ACTIVE' WHERE email = ?", email);
        return session;
    }

    private JsonNode verifiedDealer() throws Exception {
        String email = uniqueEmail("verified-dealer");
        JsonNode session = register(email, UserRole.PROFESSIONNEL);
        jdbcTemplate.update("UPDATE app_user SET email_verified_at = now(), status = 'ACTIVE' WHERE email = ?", email);
        return session;
    }

    private JsonNode verifiedAdmin() throws Exception {
        String email = uniqueEmail("admin");
        register(email, UserRole.PARTICULIER);
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN', email_verified_at = now(), status = 'ACTIVE' WHERE email = ?", email);
        return json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));
    }

    private static UUID userId(JsonNode session) {
        return UUID.fromString(session.at("/user/id").asText());
    }

    private static Map<String, Object> body(String name, Map<String, Object> criteria, Map<String, String> params) {
        return Map.of("name", name, "criteria", criteria, "params", params);
    }

    private ResultActions save(JsonNode who, String name, Map<String, Object> criteria) throws Exception {
        return save(who, name, criteria, Map.of());
    }

    private ResultActions save(JsonNode who, String name, Map<String, Object> criteria, Map<String, String> params)
            throws Exception {
        return mockMvc.perform(post(SEARCHES).header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(name, criteria, params))));
    }

    private ResultActions update(JsonNode who, String id, Map<String, Object> changes) throws Exception {
        return mockMvc.perform(patch(SEARCHES + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(changes)));
    }

    private ResultActions list(JsonNode who) throws Exception {
        return mockMvc.perform(get(SEARCHES).header(HttpHeaders.AUTHORIZATION, bearer(who)));
    }

    private String id(ResultActions response) throws Exception {
        MvcResult result = response.andReturn();
        return json(result).get("id").asText();
    }

    /** As if the search had been saved an hour ago, so that anything published counts as new. */
    private void backdate(String id) {
        jdbcTemplate.update("""
                UPDATE saved_search SET created_at = now() - interval '1 hour', last_digest_at = now() - interval '1 hour'
                 WHERE id = ?::uuid
                """, id);
    }

    private String publish(Map<String, Object> overrides) throws Exception {
        return publishAs(dealer, overrides);
    }

    private String publishAs(JsonNode seller, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.putAll(overrides);
        String listing = json(mockMvc.perform(post("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        attachReadyPhoto(listing);
        mockMvc.perform(post("/api/v1/listings/" + listing + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        return listing;
    }

    private List<String> sentFor(String savedSearch) {
        return jdbcTemplate.queryForList("SELECT listing_id::text FROM saved_search_sent WHERE saved_search_id = ?::uuid",
                String.class, savedSearch);
    }

    private long outboxRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_email WHERE email_type = 'SAVED_SEARCH_DIGEST'", Long.class);
    }

    private List<EmailMessage> sendAll() {
        poller.pollNow();
        return emails.sent();
    }

    private static int countOf(String text, String part) {
        Set<Integer> found = new HashSet<>();
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            found.add(at);
        }
        return found.size();
    }

}
