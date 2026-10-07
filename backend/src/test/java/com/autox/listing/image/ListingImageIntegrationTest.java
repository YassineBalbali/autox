package com.autox.listing.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.listing.ImageStatus;
import com.autox.listing.ListingImage;
import com.autox.listing.ListingImageRepository;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The full upload handshake against real object storage.
 *
 * <p>The PUT goes over HTTP to MinIO, exactly as a browser or phone would do it — if the
 * pre-signed URL or its required headers were wrong, these tests would fail where a mocked
 * storage layer would happily pass.
 */
class ListingImageIntegrationTest extends AbstractIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired ListingTestFixtures fixtures;
    @Autowired ListingImageRepository imageRepository;
    @Autowired ObjectStorage storage;
    @Autowired ImageProperties imageProperties;
    @Autowired ListingImageService imageService;

    @BeforeEach
    void ensureBucket() {
        storage.ensureReady();
    }

    // --- the happy path ---------------------------------------------------

    @Test
    @DisplayName("request, upload, confirm: the file never passes through the API and variants appear")
    void fullUploadHandshake() throws Exception {
        JsonNode seller = register(uniqueEmail("photographer"), UserRole.PARTICULIER);
        String listingId = createListing(seller);
        byte[] jpeg = jpegOf(1600, 1200);

        // Step 1
        JsonNode ticket = objectMapper.readTree(mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "front.jpg",
                                "contentType", "image/jpeg",
                                "byteSize", jpeg.length))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.requiredHeaders['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.confirmPath").isNotEmpty())
                .andReturn().getResponse().getContentAsString());

        String imageId = ticket.get("imageId").asText();
        assertThat(imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow().getStatus())
                .isEqualTo(ImageStatus.PENDING);

        // Step 2: straight to storage, not to us.
        assertThat(uploadTo(ticket, jpeg)).isEqualTo(200);

        // Step 3
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/" + imageId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.width").value(1600))
                .andExpect(jsonPath("$.height").value(1200))
                .andExpect(jsonPath("$.variants.THUMBNAIL.url").isNotEmpty())
                .andExpect(jsonPath("$.variants.CARD.url").isNotEmpty())
                .andExpect(jsonPath("$.variants.FULL.url").isNotEmpty())
                // Longest edge honoured, aspect ratio preserved.
                .andExpect(jsonPath("$.variants.THUMBNAIL.width").value(320))
                .andExpect(jsonPath("$.variants.THUMBNAIL.height").value(240))
                .andExpect(jsonPath("$.variants.CARD.width").value(800))
                .andExpect(jsonPath("$.variants.CARD.height").value(600));

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ImageStatus.READY);
        assertThat(stored.getChecksumSha256()).hasSize(64);
        assertThat(stored.getVariants()).hasSize(3);

        // Every object really exists in the bucket.
        for (String key : stored.allStorageKeys()) {
            assertThat(storage.stat(key)).as("object %s", key).isPresent();
        }
    }

    @Test
    @DisplayName("variants are smaller than the original and the original is never served")
    void variantsAreSmallerThanTheOriginal() throws Exception {
        JsonNode seller = register(uniqueEmail("sizes"), UserRole.PARTICULIER);
        String listingId = createListing(seller);
        String imageId = uploadImage(seller, listingId, jpegOf(2400, 1800));

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow();
        long originalBytes = storage.stat(stored.getStorageKey()).orElseThrow().byteSize();

        stored.getVariants().forEach(variant ->
                assertThat(variant.getByteSize()).as(variant.getVariant().name()).isLessThan(originalBytes));

        String body = mockMvc.perform(get("/api/v1/listings/" + listingId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The response offers variants only; the original key is not exposed at all.
        assertThat(body).doesNotContain("original.jpg").doesNotContain("storageKey");
    }

    @Test
    @DisplayName("a PNG upload is accepted and transcoded to JPEG variants")
    void pngIsAccepted() throws Exception {
        JsonNode seller = register(uniqueEmail("pnger"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] png = pngOf(1000, 1000);
        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(seller, listingId, "shot.png", "image/png", png.length));

        assertThat(uploadTo(ticket, png)).isEqualTo(200);
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/"
                        + ticket.get("imageId").asText() + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.variants.CARD.width").value(800));
    }

    @Test
    @DisplayName("confirming twice is harmless")
    void confirmIsIdempotent() throws Exception {
        JsonNode seller = register(uniqueEmail("retrier"), UserRole.PARTICULIER);
        String listingId = createListing(seller);
        String imageId = uploadImage(seller, listingId, jpegOf(900, 900));

        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/" + imageId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        assertThat(imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow().getVariants()).hasSize(3);
    }

    @Test
    @DisplayName("confirmed photos appear on the listing itself, in gallery order")
    void listingCarriesItsGallery() throws Exception {
        JsonNode seller = register(uniqueEmail("gallery"), UserRole.PARTICULIER);
        String listingId = createListing(seller);
        uploadImage(seller, listingId, jpegOf(800, 600));
        uploadImage(seller, listingId, jpegOf(800, 600));

        mockMvc.perform(get("/api/v1/listings/" + listingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].position").value(0))
                .andExpect(jsonPath("$.images[1].position").value(1));
    }

    // --- validation -------------------------------------------------------

    @Test
    @DisplayName("an unsupported content type is refused before any URL is issued")
    void unsupportedContentTypeIsRefused() throws Exception {
        JsonNode seller = register(uniqueEmail("gif-lover"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        requestSlot(seller, listingId, "animation.gif", "image/gif", 1000)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));

        assertThat(imageRepository.findByListing(UUID.fromString(listingId))).isEmpty();
    }

    @Test
    @DisplayName("a file larger than the limit is refused before any URL is issued")
    void oversizedDeclarationIsRefused() throws Exception {
        JsonNode seller = register(uniqueEmail("whale"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        requestSlot(seller, listingId, "huge.jpg", "image/jpeg", imageProperties.maxBytes() + 1)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    @DisplayName("bytes that are not an image are rejected at confirmation and the object is deleted")
    void nonImageBytesAreRejected() throws Exception {
        JsonNode seller = register(uniqueEmail("liar"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] notAnImage = "#!/bin/sh\necho definitely not a jpeg\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(seller, listingId, "payload.jpg", "image/jpeg", notAnImage.length));
        assertThat(uploadTo(ticket, notAnImage)).isEqualTo(200);

        String imageId = ticket.get("imageId").asText();
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/" + imageId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ImageStatus.FAILED);
        // The uploaded junk is not left in the bucket.
        assertThat(storage.stat(stored.getStorageKey())).isEmpty();
    }

    @Test
    @DisplayName("an image below the minimum resolution is rejected")
    void tinyImageIsRejected() throws Exception {
        JsonNode seller = register(uniqueEmail("pixel"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] tiny = jpegOf(200, 150);
        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(seller, listingId, "tiny.jpg", "image/jpeg", tiny.length));
        assertThat(uploadTo(ticket, tiny)).isEqualTo(200);

        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/"
                        + ticket.get("imageId").asText() + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_SMALL"));
    }

    @Test
    @DisplayName("confirming without uploading is a conflict, not a 404")
    void confirmWithoutUploadIsAConflict() throws Exception {
        JsonNode seller = register(uniqueEmail("forgetful"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(seller, listingId, "never.jpg", "image/jpeg", 5000));

        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/"
                        + ticket.get("imageId").asText() + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));
    }

    @Test
    @DisplayName("uploading a different number of bytes than declared is rejected by storage itself")
    void storageRejectsALengthMismatch() throws Exception {
        JsonNode seller = register(uniqueEmail("cheater"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] declared = jpegOf(800, 600);
        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(seller, listingId, "small.jpg", "image/jpeg", declared.length));

        // A much larger body against a URL signed for the smaller length.
        byte[] actual = jpegOf(2400, 1800);
        assertThat(actual.length).isNotEqualTo(declared.length);
        assertThat(uploadTo(ticket, actual)).isNotEqualTo(200);
    }

    @Test
    @DisplayName("the per-listing photo cap counts pending slots too")
    void photoCapIncludesPendingSlots() throws Exception {
        JsonNode seller = register(uniqueEmail("hoarder"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        for (int i = 0; i < imageProperties.maxPerListing(); i++) {
            requestSlot(seller, listingId, "photo" + i + ".jpg", "image/jpeg", 5000)
                    .andExpect(status().isCreated());
        }

        // Nothing has been uploaded, yet the allowance is spent — otherwise requesting
        // slots without confirming them would be an unbounded way around the cap.
        requestSlot(seller, listingId, "one-too-many.jpg", "image/jpeg", 5000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOO_MANY_IMAGES"));
    }

    // --- ordering ---------------------------------------------------------

    @Test
    @DisplayName("the gallery can be reordered in one call")
    void reorderGallery() throws Exception {
        JsonNode seller = register(uniqueEmail("arranger"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        String first = uploadImage(seller, listingId, jpegOf(800, 600));
        String second = uploadImage(seller, listingId, jpegOf(800, 600));
        String third = uploadImage(seller, listingId, jpegOf(800, 600));

        mockMvc.perform(put("/api/v1/listings/" + listingId + "/images/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("imageIds", List.of(third, first, second)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(third))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[2].id").value(second));
    }

    @Test
    @DisplayName("a partial or padded reorder is rejected rather than guessed at")
    void reorderMustBeAFullPermutation() throws Exception {
        JsonNode seller = register(uniqueEmail("partial"), UserRole.PARTICULIER);
        String listingId = createListing(seller);
        String first = uploadImage(seller, listingId, jpegOf(800, 600));
        uploadImage(seller, listingId, jpegOf(800, 600));

        // Missing one.
        mockMvc.perform(put("/api/v1/listings/" + listingId + "/images/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("imageIds", List.of(first)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Containing an id that is not ours.
        mockMvc.perform(put("/api/v1/listings/" + listingId + "/images/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("imageIds", List.of(first, UUID.randomUUID().toString())))))
                .andExpect(status().isBadRequest());

        // Duplicated.
        mockMvc.perform(put("/api/v1/listings/" + listingId + "/images/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("imageIds", List.of(first, first)))))
                .andExpect(status().isBadRequest());
    }

    // --- deletion ---------------------------------------------------------

    @Test
    @DisplayName("deleting a photo removes its objects and closes the gap in the ordering")
    void deleteRemovesObjectsAndRenumbers() throws Exception {
        JsonNode seller = register(uniqueEmail("remover"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        String first = uploadImage(seller, listingId, jpegOf(800, 600));
        String second = uploadImage(seller, listingId, jpegOf(800, 600));
        String third = uploadImage(seller, listingId, jpegOf(800, 600));

        List<String> keys = imageRepository.findWithVariants(UUID.fromString(second)).orElseThrow().allStorageKeys();

        mockMvc.perform(delete("/api/v1/listings/" + listingId + "/images/" + second)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());

        assertThat(imageRepository.findById(UUID.fromString(second))).isEmpty();
        keys.forEach(key -> assertThat(storage.stat(key)).as("object %s", key).isEmpty());

        // Positions stay contiguous from zero.
        mockMvc.perform(get("/api/v1/listings/" + listingId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[1].id").value(third))
                .andExpect(jsonPath("$[1].position").value(1));
    }

    @Test
    @DisplayName("deleting a photo frees a slot under the per-listing cap")
    void deletingFreesCapacity() throws Exception {
        JsonNode seller = register(uniqueEmail("swapper"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        String doomed = uploadImage(seller, listingId, jpegOf(800, 600));
        for (int i = 1; i < imageProperties.maxPerListing(); i++) {
            requestSlot(seller, listingId, "p" + i + ".jpg", "image/jpeg", 5000).andExpect(status().isCreated());
        }
        requestSlot(seller, listingId, "blocked.jpg", "image/jpeg", 5000).andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/listings/" + listingId + "/images/" + doomed)
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());

        requestSlot(seller, listingId, "now-ok.jpg", "image/jpeg", 5000).andExpect(status().isCreated());
    }

    // --- orphan cleanup ---------------------------------------------------

    @Test
    @DisplayName("abandoned upload slots are swept, including bytes uploaded but never confirmed")
    void abandonedUploadsAreSwept() throws Exception {
        JsonNode seller = register(uniqueEmail("abandoner"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] jpeg = jpegOf(800, 600);
        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(seller, listingId, "orphan.jpg", "image/jpeg", jpeg.length));
        // Uploaded, never confirmed: the worst case, because the bytes are billable.
        assertThat(uploadTo(ticket, jpeg)).isEqualTo(200);

        UUID imageId = UUID.fromString(ticket.get("imageId").asText());
        String key = imageRepository.findById(imageId).orElseThrow().getStorageKey();
        assertThat(storage.stat(key)).isPresent();

        // Nothing is swept while the URL is still valid.
        assertThat(imageService.purgeAbandonedUploads()).isZero();

        // Back-date the slot's expiry rather than waiting out the TTL.
        jdbcTemplate.update("UPDATE listing_image SET upload_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), imageId);

        assertThat(imageService.purgeAbandonedUploads()).isEqualTo(1);
        assertThat(imageRepository.findById(imageId)).isEmpty();
        assertThat(storage.stat(key)).isEmpty();
    }

    @Test
    @DisplayName("the sweeper leaves confirmed photos alone")
    void sweeperIgnoresReadyImages() throws Exception {
        JsonNode seller = register(uniqueEmail("safe"), UserRole.PARTICULIER);
        String listingId = createListing(seller);
        String imageId = uploadImage(seller, listingId, jpegOf(800, 600));

        jdbcTemplate.update("UPDATE listing_image SET upload_expires_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(imageService.purgeAbandonedUploads()).isZero();
        assertThat(imageRepository.findById(UUID.fromString(imageId))).isPresent();
    }

    // --- access control ---------------------------------------------------

    @Test
    @DisplayName("a stranger cannot add, list, reorder or delete another seller's photos")
    void strangersAreRefused() throws Exception {
        JsonNode owner = register(uniqueEmail("photo-owner"), UserRole.PARTICULIER);
        JsonNode stranger = register(uniqueEmail("photo-thief"), UserRole.PARTICULIER);
        String listingId = createListing(owner);
        String imageId = uploadImage(owner, listingId, jpegOf(800, 600));

        String auth = bearer(stranger);
        requestSlotAs(auth, listingId, "mine.jpg", "image/jpeg", 5000).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/listings/" + listingId + "/images").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/listings/" + listingId + "/images/" + imageId)
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/listings/" + listingId + "/images/order")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("imageIds", List.of(imageId)))))
                .andExpect(status().isNotFound());

        assertThat(imageRepository.findById(UUID.fromString(imageId))).isPresent();
    }

    @Test
    @DisplayName("an admin can manage any listing's photos")
    void adminCanManagePhotos() throws Exception {
        JsonNode owner = register(uniqueEmail("admin-photo-subject"), UserRole.PARTICULIER);
        String listingId = createListing(owner);
        String imageId = uploadImage(owner, listingId, jpegOf(800, 600));

        createUser(uniqueEmail("photo-admin"), UserRole.ADMIN, UserStatus.ACTIVE);
        JsonNode admin = loginAsOnlyAdmin();

        mockMvc.perform(get("/api/v1/listings/" + listingId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/v1/listings/" + listingId + "/images/" + imageId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("uploading requires authentication")
    void uploadRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + UUID.randomUUID() + "/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "x.jpg", "contentType", "image/jpeg", "byteSize", 1000))))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ----------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions requestSlot(
            JsonNode session, String listingId, String filename, String contentType, long byteSize)
            throws Exception {
        return requestSlotAs(bearer(session), listingId, filename, contentType, byteSize);
    }

    private org.springframework.test.web.servlet.ResultActions requestSlotAs(
            String authorization, String listingId, String filename, String contentType, long byteSize)
            throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "filename", filename, "contentType", contentType, "byteSize", byteSize))));
    }

    private String requestSlotBody(JsonNode session, String listingId, String filename,
                                   String contentType, long byteSize) throws Exception {
        return requestSlot(session, listingId, filename, contentType, byteSize)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** Requests a slot, PUTs the bytes to storage and confirms. Returns the image id. */
    private String uploadImage(JsonNode session, String listingId, byte[] jpeg) throws Exception {
        JsonNode ticket = objectMapper.readTree(
                requestSlotBody(session, listingId, "photo.jpg", "image/jpeg", jpeg.length));
        assertThat(uploadTo(ticket, jpeg)).isEqualTo(200);

        String imageId = ticket.get("imageId").asText();
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/" + imageId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
        return imageId;
    }

    /** A real HTTP PUT to the pre-signed URL, sending exactly the headers it requires. */
    private static int uploadTo(JsonNode ticket, byte[] content) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ticket.get("uploadUrl").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));

        ticket.get("requiredHeaders").fields().forEachRemaining(header -> {
            // Content-Length is set by the client library and is restricted here.
            if (!header.getKey().equalsIgnoreCase("Content-Length")) {
                request.header(header.getKey(), header.getValue().asText());
            }
        });

        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] jpegOf(int width, int height) throws Exception {
        return encode(drawPhoto(width, height), "jpeg");
    }

    private static byte[] pngOf(int width, int height) throws Exception {
        return encode(drawPhoto(width, height), "png");
    }

    /** Gradient plus shapes: compresses like a photograph rather than to almost nothing. */
    private static BufferedImage drawPhoto(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int y = 0; y < height; y++) {
                graphics.setColor(new Color(40 + (y * 180 / Math.max(1, height)), 90, 160));
                graphics.drawLine(0, y, width, y);
            }
            graphics.setColor(Color.WHITE);
            graphics.fillOval(width / 8, height / 8, width / 3, height / 3);
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(width / 2, height / 2, width / 3, height / 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    private String createListing(JsonNode session) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private JsonNode loginAsOnlyAdmin() throws Exception {
        String adminEmail = users.findAllActiveByRole(UserRole.ADMIN,
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst().getEmail();

        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", adminEmail, "password", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
