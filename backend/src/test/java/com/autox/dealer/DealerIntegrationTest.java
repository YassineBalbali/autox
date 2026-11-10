package com.autox.dealer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.storage.ObjectStorage;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The directory of professionals, their pages, and what may and may not appear on them. */
class DealerIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    private static final Map<String, Object> LOA = Map.of(
            "offerType", "LEASING",
            "type", "LOA",
            "monthlyPayment", 25_000L,
            "firstPayment", 200_000L,
            "durationMonths", 37,
            "mileagePerYear", 15_000,
            "optionPrice", 1_000_000L);

    @Autowired ObjectStorage storage;

    // ---- the directory --------------------------------------------------------------------

    @Nested
    @DisplayName("the directory")
    class Directory {

        @Test
        @DisplayName("lists professionals with a listing online, counted by offer, and nobody else")
        void whoIsListed() throws Exception {
            JsonNode listed = dealer("Garage Bellevue");
            listing(listed, "Lyon", "69003");
            listing(listed, "Lyon", "69003");
            leasing(listed, "Lyon", "69003");

            dealer("Garage Sans Annonce");
            JsonNode suspended = dealer("Garage Suspendu");
            listing(suspended, "Lyon", "69003");
            jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?::uuid", id(suspended));
            JsonNode deleted = dealer("Garage Supprimé");
            listing(deleted, "Lyon", "69003");
            jdbcTemplate.update("UPDATE app_user SET status = 'DELETED', deleted_at = now() WHERE id = ?::uuid", id(deleted));
            JsonNode hidden = dealer("Garage Masqué");
            listing(hidden, "Lyon", "69003");
            hide(hidden, "Fausses annonces");
            JsonNode person = register(uniqueEmail("particulier"), UserRole.PARTICULIER);
            listing(person, "Lyon", "69003");

            JsonNode directory = directory("");

            assertThat(directory.get("totalElements").asInt()).isOne();
            JsonNode card = directory.at("/items/0");
            assertThat(card.get("name").asText()).isEqualTo("Garage Bellevue");
            assertThat(card.get("slug").asText()).isEqualTo("garage-bellevue");
            assertThat(card.get("saleListings").asInt()).isEqualTo(2);
            assertThat(card.get("leasingListings").asInt()).isOne();
            assertThat(card.get("city").asText()).isEqualTo("Lyon");
            assertThat(card.get("departement").asText()).isEqualTo("69");
            assertThat(card.get("memberSince").asText()).isNotBlank();
            assertThat(card.path("logoUrl").isMissingNode() || card.get("logoUrl").isNull()).isTrue();
        }

        @Test
        @DisplayName("is searched by name, accents aside, and filtered by département or town")
        void filters() throws Exception {
            listing(dealer("Écluse Automobiles"), "Mérignac", "33700");
            listing(dealer("Garage du Port"), "Bordeaux", "33000");
            listing(dealer("Nord VO"), "Lille", "59000");

            assertThat(names(directory("?q=ecluse"))).containsExactly("Écluse Automobiles");
            assertThat(names(directory("?q=GARAGE"))).containsExactly("Garage du Port");
            assertThat(names(directory("?departement=33&sort=NAME"))).containsExactly("Écluse Automobiles", "Garage du Port");
            assertThat(names(directory("?city=merignac"))).containsExactly("Écluse Automobiles");
            assertThat(names(directory("?city=590"))).containsExactly("Nord VO");
            assertThat(names(directory("?q=nobody"))).isEmpty();

            // The département choices: every one with a dealer, whatever the filter.
            JsonNode departements = directory("?q=nord").get("departements");
            assertThat(departements).hasSize(2);
            assertThat(departements.get(0).get("code").asText()).isEqualTo("33");
            assertThat(departements.get(0).get("dealers").asInt()).isEqualTo(2);
            assertThat(departements.get(1).get("code").asText()).isEqualTo("59");

            mockMvc.perform(get("/api/v1/dealers?departement=Gironde")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is sorted by listings online or by name, a page at a time")
        void sortAndPages() throws Exception {
            JsonNode small = dealer("Alpha Autos");
            listing(small, "Lyon", "69001");
            JsonNode big = dealer("Zeta Autos");
            listing(big, "Lyon", "69002");
            listing(big, "Lyon", "69002");

            assertThat(names(directory("?sort=LISTINGS"))).containsExactly("Zeta Autos", "Alpha Autos");
            assertThat(names(directory("?sort=NAME"))).containsExactly("Alpha Autos", "Zeta Autos");

            JsonNode first = directory("?sort=NAME&size=1");
            assertThat(names(first)).containsExactly("Alpha Autos");
            assertThat(first.get("hasNext").asBoolean()).isTrue();
            assertThat(first.get("totalPages").asInt()).isEqualTo(2);
            JsonNode second = directory("?sort=NAME&size=1&page=1");
            assertThat(names(second)).containsExactly("Zeta Autos");
            assertThat(second.get("hasNext").asBoolean()).isFalse();
        }
    }

    // ---- slugs ----------------------------------------------------------------------------

    @Nested
    @DisplayName("a dealer page's address")
    class Slugs {

        @Test
        @DisplayName("is made from the name, without accents, and is unique")
        void madeFromTheName() throws Exception {
            assertThat(slug(dealer("Garage Bellevue"))).isEqualTo("garage-bellevue");
            assertThat(slug(dealer("Garage Bellevue"))).isEqualTo("garage-bellevue-2");
            assertThat(slug(dealer("Garage  Bellevue !"))).isEqualTo("garage-bellevue-3");
            assertThat(slug(dealer("Écluse & Fils — Automobiles"))).isEqualTo("ecluse-fils-automobiles");
        }

        @Test
        @DisplayName("stays the same when the dealer is renamed, and cannot be changed at all")
        void stable() throws Exception {
            JsonNode dealer = dealer("Garage Bellevue");
            mockMvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(dealer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("displayName", "Bellevue Automobiles",
                                    "professional", Map.of("companyName", "Bellevue Automobiles SAS")))))
                    .andExpect(status().isOk());

            assertThat(slug(dealer)).isEqualTo("garage-bellevue");
            page("garage-bellevue", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Bellevue Automobiles"));
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE pro_profile SET slug = 'autre' WHERE user_id = ?::uuid", id(dealer)))
                    .hasMessageContaining("slug does not change");
        }
    }

    // ---- the page -------------------------------------------------------------------------

    @Nested
    @DisplayName("the page")
    class Page {

        @Test
        @DisplayName("says what the dealer wrote, and the e-mail address never; the phone only if they chose")
        void contactDetails() throws Exception {
            String email = uniqueEmail("garage-contact");
            JsonNode dealer = dealer("Garage Contact", email);
            listing(dealer, "Lyon", "69003");
            jdbcTemplate.update("UPDATE app_user SET phone_e164 = '+33611111111' WHERE id = ?::uuid", id(dealer));
            updateCompany(dealer, Map.of("contactPhone", "+33472000000", "description", "Garage familial.",
                    "openingHours", "Lundi au samedi : 9 h – 19 h", "websiteUrl", "https://garage-contact.example"))
                    .andExpect(status().isOk());

            String hidden = page("garage-contact", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("Garage familial."))
                    .andExpect(jsonPath("$.openingHours").value("Lundi au samedi : 9 h – 19 h"))
                    .andExpect(jsonPath("$.websiteUrl").value("https://garage-contact.example"))
                    .andExpect(jsonPath("$.saleListings").value(1))
                    .andExpect(jsonPath("$.phone").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(hidden).doesNotContain(email).doesNotContain("+33611111111").doesNotContain("+33472000000");

            updateCompany(dealer, Map.of("showPhone", true)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.professional.showPhone").value(true));

            String shown = page("garage-contact", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.phone").value("+33472000000"))
                    .andReturn().getResponse().getContentAsString();
            // The company's number, which the dealer chose to show: never the account's own, nor the address.
            assertThat(shown).doesNotContain(email).doesNotContain("+33611111111");
        }

        @Test
        @DisplayName("hidden by an administrator, is a 404 to the public, and still there for the dealer, with why")
        void hidden() throws Exception {
            JsonNode dealer = dealer("Garage Masqué");
            listing(dealer, "Lyon", "69003");
            JsonNode admin = admin();
            hide(dealer, "Annonces trompeuses répétées");

            page("garage-masque", null).andExpect(status().isNotFound());
            page("garage-masque", register(uniqueEmail("visiteur"), UserRole.PARTICULIER)).andExpect(status().isNotFound());
            page("garage-masque", dealer).andExpect(status().isOk())
                    .andExpect(jsonPath("$.own").value(true))
                    .andExpect(jsonPath("$.hiddenReason").value("Annonces trompeuses répétées"));
            page("garage-masque", admin).andExpect(status().isOk())
                    .andExpect(jsonPath("$.hiddenAt").exists());
        }

        @Test
        @DisplayName("of a suspended dealer, is a 404 and out of the directory; an unknown address too")
        void suspended() throws Exception {
            JsonNode dealer = dealer("Garage Suspendu");
            listing(dealer, "Lyon", "69003");
            page("garage-suspendu", null).andExpect(status().isOk());

            jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?::uuid", id(dealer));

            page("garage-suspendu", null).andExpect(status().isNotFound());
            assertThat(directory("").get("totalElements").asInt()).isZero();
            page("personne", null).andExpect(status().isNotFound());
            page("Pas-Un-Slug", null).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is where a dealer's listing links to, and a private seller's links nowhere")
        void fromTheListing() throws Exception {
            JsonNode dealer = dealer("Garage Bellevue");
            String dealerListing = listing(dealer, "Lyon", "69003");
            String privateListing = listing(register(uniqueEmail("particulier"), UserRole.PARTICULIER), "Lyon", "69003");

            mockMvc.perform(get("/api/v1/listings/" + dealerListing))
                    .andExpect(jsonPath("$.seller.dealerSlug").value("garage-bellevue"));
            mockMvc.perform(get("/api/v1/listings/" + privateListing))
                    .andExpect(jsonPath("$.seller.dealerSlug").doesNotExist());

            hide(dealer, "Vérification en cours");
            mockMvc.perform(get("/api/v1/listings/" + dealerListing))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.seller.dealerSlug").doesNotExist());
        }
    }

    // ---- the listings: the search, with one criterion more ----------------------------------

    @Test
    @DisplayName("a dealer's listings are the public search with sellerId, every filter and the offer included")
    void listingsAreTheSearch() throws Exception {
        JsonNode dealer = dealer("Garage Bellevue");
        String sale = listing(dealer, "Lyon", "69003");
        String cheap = listing(dealer, "Lyon", "69003", Map.of("priceAmount", 500_000L));
        String lease = leasing(dealer, "Lyon", "69003");
        String other = listing(dealer("Autre Garage"), "Lyon", "69003");
        String seller = id(dealer);

        assertThat(ids("/api/v1/listings?sellerId=" + seller)).containsExactlyInAnyOrder(sale, cheap);
        assertThat(ids("/api/v1/listings?sellerId=" + seller + "&priceMax=600000")).containsExactly(cheap);
        assertThat(ids("/api/v1/listings?sellerId=" + seller + "&offer=LEASING")).containsExactly(lease);
        assertThat(ids("/api/v1/listings?sellerId=" + seller + "&sort=PRICE_ASC")).containsExactly(cheap, sale);
        assertThat(ids("/api/v1/listings")).contains(other);
        mockMvc.perform(get("/api/v1/listings/count?sellerId=" + seller))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @DisplayName("the page's counts, its tabs and its directory card are the search's, damaged and non-public listings aside")
    void countsAreTheSearchs() throws Exception {
        JsonNode dealer = dealer("Sud Automobiles");
        listing(dealer, "Marseille", "13008");
        listing(dealer, "Marseille", "13008");
        damage(listing(dealer, "Marseille", "13008"));
        leasing(dealer, "Marseille", "13008");
        // A leasing offer cannot be damaged (ck_listing_leasing_not_damaged): an expired one instead.
        jdbcTemplate.update("UPDATE listing SET expires_at = now() - interval '1 day' WHERE id = ?::uuid",
                leasing(dealer, "Marseille", "13008"));
        // Not public, each its own way: a draft, an expired one, a rejected one, a deleted one.
        draft(dealer);
        jdbcTemplate.update("UPDATE listing SET expires_at = now() - interval '1 day' WHERE id = ?::uuid",
                listing(dealer, "Marseille", "13008"));
        jdbcTemplate.update("UPDATE listing SET moderation_status = 'REJECTED' WHERE id = ?::uuid",
                listing(dealer, "Marseille", "13008"));
        jdbcTemplate.update("UPDATE listing SET deleted_at = now() WHERE id = ?::uuid",
                listing(dealer, "Marseille", "13008"));
        String seller = id(dealer);

        // What the search lists on the page, tab by tab, before any filter.
        int sales = ids("/api/v1/listings?sellerId=" + seller).size();
        int leases = ids("/api/v1/listings?sellerId=" + seller + "&offer=LEASING").size();
        assertThat(sales).isEqualTo(2);
        assertThat(leases).isOne();
        mockMvc.perform(get("/api/v1/listings/count?sellerId=" + seller)).andExpect(jsonPath("$.count").value(sales));
        mockMvc.perform(get("/api/v1/listings/count?sellerId=" + seller + "&offer=LEASING"))
                .andExpect(jsonPath("$.count").value(leases));

        // The page's header and the directory card say the same.
        page("sud-automobiles", null)
                .andExpect(jsonPath("$.saleListings").value(sales))
                .andExpect(jsonPath("$.leasingListings").value(leases));
        JsonNode card = directory("").at("/items/0");
        assertThat(card.get("slug").asText()).isEqualTo("sud-automobiles");
        assertThat(card.get("saleListings").asInt()).isEqualTo(sales);
        assertThat(card.get("leasingListings").asInt()).isEqualTo(leases);

        // The damaged ones are only out of the defaults: asked for, the search shows them.
        assertThat(ids("/api/v1/listings?sellerId=" + seller + "&damage=ALL")).hasSize(3);
    }

    @Test
    @DisplayName("a dealer whose only listings are damaged is out of the directory, as the search shows none of them")
    void onlyDamaged() throws Exception {
        JsonNode dealer = dealer("Casse Auto");
        damage(listing(dealer, "Lyon", "69003"));

        assertThat(directory("").get("totalElements").asInt()).isZero();
        page("casse-auto", null).andExpect(status().isOk()).andExpect(jsonPath("$.saleListings").value(0));
    }

    // ---- writing to a dealer -----------------------------------------------------------------

    @Test
    @DisplayName("a visitor writes from the page through AutoX's messaging, once per dealer, and the inbox says from where")
    void contact() throws Exception {
        JsonNode dealer = dealer("Garage Bellevue");
        listing(dealer, "Lyon", "69003");
        JsonNode visitor = register(uniqueEmail("visiteur"), UserRole.PARTICULIER);

        mockMvc.perform(post("/api/v1/dealers/garage-bellevue/conversation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Bonjour\"}")).andExpect(status().isUnauthorized());

        String first = json(contactDealer(visitor, "garage-bellevue", "Bonjour, êtes-vous ouverts samedi ?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealerPage.slug").value("garage-bellevue"))
                .andExpect(jsonPath("$.listing").doesNotExist())
                .andExpect(jsonPath("$.canReply").value(true))
                .andReturn()).get("id").asText();
        String again = json(contactDealer(visitor, "garage-bellevue", "Et dimanche ?")
                .andExpect(status().isOk()).andReturn()).get("id").asText();
        assertThat(again).isEqualTo(first);

        mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.items[0].dealerPage.slug").value("garage-bellevue"))
                .andExpect(jsonPath("$.items[0].role").value("SELLER"));
        mockMvc.perform(post("/api/v1/conversations/" + first + "/messages").header(HttpHeaders.AUTHORIZATION, bearer(dealer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Oui, jusqu’à midi.\"}"))
                .andExpect(status().isOk());

        contactDealer(dealer, "garage-bellevue", "Moi-même").andExpect(status().isForbidden());
        hide(dealer, "Vérification en cours");
        contactDealer(register(uniqueEmail("autre"), UserRole.PARTICULIER), "garage-bellevue", "Bonjour")
                .andExpect(status().isNotFound());
    }

    // ---- administrators -----------------------------------------------------------------------

    @Test
    @DisplayName("an administrator hides a page with a reason, recorded like other actions, and can put it back")
    void adminHidesAndShows() throws Exception {
        JsonNode dealer = dealer("Garage Bellevue");
        listing(dealer, "Lyon", "69003");
        JsonNode admin = admin();
        String path = "/api/v1/admin/users/" + id(dealer) + "/dealer-page/";

        mockMvc.perform(post(path + "hide").header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(path + "hide").header(HttpHeaders.AUTHORIZATION, bearer(dealer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Moi\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(path + "hide").header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Fausses coordonnées\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.dealerPage.slug").value("garage-bellevue"))
                .andExpect(jsonPath("$.user.dealerPage.hiddenReason").value("Fausses coordonnées"))
                .andExpect(jsonPath("$.user.actions[0].action").value("HIDE_DEALER_PAGE"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reason FROM admin_user_action WHERE target_user_id = ?::uuid AND action = 'HIDE_DEALER_PAGE'""",
                String.class, id(dealer))).isEqualTo("Fausses coordonnées");
        page("garage-bellevue", null).andExpect(status().isNotFound());
        mockMvc.perform(post(path + "hide").header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Encore\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post(path + "show").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.dealerPage.hiddenAt").doesNotExist());
        page("garage-bellevue", null).andExpect(status().isOk());

        JsonNode person = register(uniqueEmail("particulier"), UserRole.PARTICULIER);
        mockMvc.perform(post("/api/v1/admin/users/" + id(person) + "/dealer-page/hide")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rien\"}"))
                .andExpect(status().isConflict());
    }

    // ---- the dealer's side ----------------------------------------------------------------------

    @Test
    @DisplayName("the website is an http or https address, nothing else")
    void website() throws Exception {
        JsonNode dealer = dealer("Garage Bellevue");
        for (String refused : List.of("javascript:alert(1)", "garage.fr", "ftp://garage.fr", "https://")) {
            updateCompany(dealer, Map.of("websiteUrl", refused)).andExpect(status().isBadRequest());
        }
        updateCompany(dealer, Map.of("websiteUrl", "HTTPS://www.garage-bellevue.example/occasions"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE pro_profile SET website_url = 'javascript:alert(1)' WHERE user_id = ?::uuid", id(dealer)))
                .hasMessageContaining("ck_pro_profile_website");
    }

    @Test
    @DisplayName("the logo goes through the photo pipeline: EXIF removed, resized, and replaced or removed in storage")
    void logo() throws Exception {
        storage.ensureReady();
        JsonNode dealer = dealer("Garage Bellevue");
        listing(dealer, "Lyon", "69003");

        JsonNode profile = uploadLogo(dealer, jpegWithExif(1200, 800));
        String url = profile.at("/professional/logoUrl").asText();
        assertThat(url).isNotBlank();
        String key = jdbcTemplate.queryForObject("SELECT logo_storage_key FROM pro_profile WHERE user_id = ?::uuid",
                String.class, id(dealer));
        byte[] stored = storage.read(key);
        assertThat(contains(stored, "Exif")).as("no EXIF segment").isFalse();
        assertThat(contains(stored, "GPS-SECRET")).as("no GPS data").isFalse();
        BufferedImage logo = ImageIO.read(new java.io.ByteArrayInputStream(stored));
        assertThat(logo.getWidth()).isEqualTo(CompanyLogoService.MAX_EDGE);
        assertThat(logo.getHeight()).isEqualTo(267);
        page("garage-bellevue", null).andExpect(jsonPath("$.logoUrl").isNotEmpty());
        assertThat(directory("").at("/items/0/logoUrl").asText()).isNotBlank();

        uploadLogo(dealer, jpegWithExif(600, 600));
        assertThat(storage.stat(key)).as("the previous logo is deleted").isEmpty();

        String second = jdbcTemplate.queryForObject("SELECT logo_storage_key FROM pro_profile WHERE user_id = ?::uuid",
                String.class, id(dealer));
        mockMvc.perform(delete("/api/v1/me/company/logo").header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professional.logoUrl").doesNotExist());
        assertThat(storage.stat(second)).isEmpty();

        JsonNode person = register(uniqueEmail("particulier"), UserRole.PARTICULIER);
        mockMvc.perform(post("/api/v1/me/company/logo").header(HttpHeaders.AUTHORIZATION, bearer(person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"logo.jpg\",\"contentType\":\"image/jpeg\",\"byteSize\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a logo upload never confirmed is swept with its object by the image clean-up, once its slot has expired")
    void abandonedLogoUploads() throws Exception {
        storage.ensureReady();
        JsonNode dealer = dealer("Garage Bellevue");
        String abandoned = putLogo(dealer, jpegWithExif(600, 600));
        String inFlight = putLogo(dealer, jpegWithExif(600, 600));
        String abandonedKey = "dealers/" + id(dealer) + "/uploads/" + abandoned;
        String inFlightKey = "dealers/" + id(dealer) + "/uploads/" + inFlight;
        jdbcTemplate.update("UPDATE dealer_logo_upload SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid",
                abandoned);
        assertThat(storage.stat(abandonedKey)).isPresent();

        sweeper.purgeAbandonedUploads();

        assertThat(storage.stat(abandonedKey)).as("the abandoned upload").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dealer_logo_upload WHERE id = ?::uuid",
                Integer.class, abandoned)).isZero();
        assertThat(storage.stat(inFlightKey)).as("an upload still in time").isPresent();
        // A swept slot cannot be confirmed; the one still in time can, and is then gone from the table.
        mockMvc.perform(post("/api/v1/me/company/logo/" + abandoned + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/me/company/logo/" + inFlight + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dealer_logo_upload", Integer.class)).isZero();
        assertThat(storage.stat(inFlightKey)).as("the upload, once made into the logo").isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    @Autowired com.autox.listing.image.ListingImageSweeper sweeper;

    /** Asks for a slot and PUTs the bytes, without confirming. Returns the upload's id. */
    private String putLogo(JsonNode dealer, byte[] content) throws Exception {
        JsonNode ticket = json(mockMvc.perform(post("/api/v1/me/company/logo")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dealer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "logo.jpg", "contentType", "image/jpeg", "byteSize", content.length))))
                .andExpect(status().isCreated()).andReturn());
        HttpRequest put = HttpRequest.newBuilder(URI.create(ticket.get("uploadUrl").asText()))
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThat(http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        }
        return ticket.get("imageId").asText();
    }

    private JsonNode dealer(String name) throws Exception {
        return dealer(name, uniqueEmail("dealer"));
    }

    /** A dealer registered under this name, as a sign-up makes one, verified. */
    private JsonNode dealer(String name, String email) throws Exception {
        Map<String, Object> payload = registrationPayload(email, UserRole.PROFESSIONNEL);
        payload.put("displayName", name);
        payload.put("companyName", name);
        JsonNode auth = json(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andExpect(status().isCreated()).andReturn());
        jdbcTemplate.update("UPDATE app_user SET email_verification_required = false WHERE email = ?", email);
        return auth;
    }

    private JsonNode admin() throws Exception {
        String email = uniqueEmail("dealer-admin");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        return json(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn());
    }

    private void hide(JsonNode dealer, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/" + id(dealer) + "/dealer-page/hide")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().isOk());
    }

    private static String id(JsonNode auth) {
        return auth.at("/user/id").asText();
    }

    private String slug(JsonNode dealer) {
        return jdbcTemplate.queryForObject("SELECT slug FROM pro_profile WHERE user_id = ?::uuid", String.class, id(dealer));
    }

    private String listing(JsonNode owner, String city, String postalCode) throws Exception {
        return listing(owner, city, postalCode, Map.of());
    }

    private String leasing(JsonNode owner, String city, String postalCode) throws Exception {
        return listing(owner, city, postalCode, Map.of("offer", LOA));
    }

    /** A published listing, with a photo. */
    private String listing(JsonNode owner, String city, String postalCode, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.put("city", city);
        payload.put("postalCode", postalCode);
        payload.putAll(overrides);
        String id = json(mockMvc.perform(post("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    /** Declares the listing damaged, as the editor would. */
    private void damage(String listingId) {
        jdbcTemplate.update("""
                UPDATE listing SET damaged = true, damage_types = ARRAY['BODYWORK'],
                                   damage_description = 'Aile avant enfoncée.' WHERE id = ?::uuid""", listingId);
    }

    /** Created and never published. */
    private void draft(JsonNode owner) throws Exception {
        mockMvc.perform(post("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated());
    }

    private JsonNode directory(String query) throws Exception {
        return json(mockMvc.perform(get("/api/v1/dealers" + query)).andExpect(status().isOk()).andReturn());
    }

    private static List<String> names(JsonNode directory) {
        List<String> names = new ArrayList<>();
        directory.get("items").forEach(card -> names.add(card.get("name").asText()));
        return names;
    }

    private List<String> ids(String path) throws Exception {
        List<String> ids = new ArrayList<>();
        json(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn())
                .get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private ResultActions page(String slug, JsonNode viewer) throws Exception {
        var request = get("/api/v1/dealers/" + slug);
        if (viewer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(viewer));
        }
        return mockMvc.perform(request);
    }

    private ResultActions updateCompany(JsonNode dealer, Map<String, Object> professional) throws Exception {
        return mockMvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(dealer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("professional", professional))));
    }

    private ResultActions contactDealer(JsonNode who, String slug, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/dealers/" + slug + "/conversation")
                .header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("body", body))));
    }

    /** Asks for a slot, PUTs the bytes to storage as a browser would, and confirms. */
    private JsonNode uploadLogo(JsonNode dealer, byte[] content) throws Exception {
        JsonNode ticket = json(mockMvc.perform(post("/api/v1/me/company/logo")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dealer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "logo.jpg", "contentType", "image/jpeg", "byteSize", content.length))))
                .andExpect(status().isCreated()).andReturn());
        HttpRequest put = HttpRequest.newBuilder(URI.create(ticket.get("uploadUrl").asText()))
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThat(http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        }
        MvcResult confirmed = mockMvc.perform(post(ticket.get("confirmPath").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().isOk()).andReturn();
        return json(confirmed);
    }

    private static boolean contains(byte[] content, String needle) {
        return new String(content, StandardCharsets.ISO_8859_1).contains(needle);
    }

    /** A JPEG with an EXIF segment carrying a stand-in for a GPS position. */
    private static byte[] jpegWithExif(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(20, 90, 160));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.WHITE);
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", plain);
        byte[] jpeg = plain.toByteArray();

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write("Exif".getBytes(StandardCharsets.US_ASCII));
        app1.write(new byte[]{0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0});
        app1.write("GPS-SECRET 45.7640N 4.8357E".getBytes(StandardCharsets.US_ASCII));
        byte[] payload = app1.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(((payload.length + 2) >> 8) & 0xFF);
        out.write((payload.length + 2) & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
