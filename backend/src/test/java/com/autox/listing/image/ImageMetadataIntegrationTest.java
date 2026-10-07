package com.autox.listing.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.listing.ListingImage;
import com.autox.listing.ListingImageRepository;
import com.autox.storage.ObjectStorage;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
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
 * Metadata removal and orientation handling, exercised with a JPEG that really carries an
 * EXIF segment.
 *
 * <p>Written after a bug that every other image test missed: the files those tests generate
 * have no EXIF at all, so the orientation path was never executed. A photograph from a phone
 * always has one.
 */
class ImageMetadataIntegrationTest extends AbstractIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String GPS_MARKER = "GPS:48.8584,2.2945 HOME";

    @Autowired ListingImageRepository imageRepository;
    @Autowired ObjectStorage storage;

    @BeforeEach
    void ensureBucket() {
        storage.ensureReady();
    }

    @Test
    @DisplayName("EXIF is removed from the stored original, GPS data included")
    void exifIsStrippedFromTheStoredOriginal() throws Exception {
        JsonNode seller = register(uniqueEmail("exif-seller"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] uploaded = jpegWithExif(1600, 1200, 1);
        assertThat(containsMarker(uploaded)).as("the fixture really carries the GPS string").isTrue();

        String imageId = uploadAndConfirm(seller, listingId, uploaded);

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow();
        byte[] original = storage.read(stored.getStorageKey());

        assertThat(containsMarker(original)).as("GPS data must not survive in storage").isFalse();
        assertThat(containsAscii(original, "Exif")).as("no EXIF marker remains").isFalse();
        // The recorded size matches what is actually stored, not what was uploaded.
        assertThat(stored.getByteSize()).isEqualTo(original.length);
        assertThat(original.length).isLessThan(uploaded.length);

        // Still a valid, decodable image of the same size.
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(original));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1600);
        assertThat(decoded.getHeight()).isEqualTo(1200);
    }

    @Test
    @DisplayName("a rotated photo is turned upright, and the variants follow")
    void orientationIsAppliedBeforeTheTagIsDiscarded() throws Exception {
        JsonNode seller = register(uniqueEmail("rotated"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        // Orientation 6: "rotate 90° clockwise to display", as a phone held upright writes it.
        byte[] uploaded = jpegWithExif(1600, 1200, 6);

        String body = confirm(seller, listingId, requestAndPut(seller, listingId, uploaded));
        JsonNode image = objectMapper.readTree(body);

        // Landscape pixels plus a rotate-right tag means a portrait photograph.
        assertThat(image.get("width").asInt()).isEqualTo(1200);
        assertThat(image.get("height").asInt()).isEqualTo(1600);
        // The longest edge of the thumbnail is its height now, not its width.
        assertThat(image.get("variants").get("THUMBNAIL").get("height").asInt()).isEqualTo(320);
        assertThat(image.get("variants").get("THUMBNAIL").get("width").asInt()).isEqualTo(240);

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(
                image.get("id").asText())).orElseThrow();
        byte[] original = storage.read(stored.getStorageKey());

        // The stored original is upright on its own, with nothing left to interpret.
        assertThat(containsAscii(original, "Exif")).isFalse();
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(original));
        assertThat(decoded.getWidth()).isEqualTo(1200);
        assertThat(decoded.getHeight()).isEqualTo(1600);
    }

    @Test
    @DisplayName("an upright photo keeps its exact pixels — stripping is lossless")
    void uprightPhotoIsNotReEncoded() throws Exception {
        JsonNode seller = register(uniqueEmail("lossless"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] uploaded = jpegWithExif(1200, 900, 1);
        String imageId = uploadAndConfirm(seller, listingId, uploaded);

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow();
        byte[] original = storage.read(stored.getStorageKey());

        // Only the metadata segment is gone; the compressed scan data is byte-identical.
        int removed = uploaded.length - original.length;
        assertThat(removed).isGreaterThan(0).isLessThan(500);
        assertThat(lastBytes(original, 2048)).isEqualTo(lastBytes(uploaded, 2048));
    }

    @Test
    @DisplayName("a photo with no metadata at all is left exactly as uploaded")
    void cleanPhotoIsUntouched() throws Exception {
        JsonNode seller = register(uniqueEmail("already-clean"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] uploaded = plainJpeg(1000, 800);
        String imageId = uploadAndConfirm(seller, listingId, uploaded);

        ListingImage stored = imageRepository.findWithVariants(UUID.fromString(imageId)).orElseThrow();
        byte[] original = storage.read(stored.getStorageKey());

        // ImageIO writes a JFIF APP0 segment, which is also metadata and also goes.
        assertThat(original.length).isLessThanOrEqualTo(uploaded.length);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(original))).isNotNull();
    }

    @Test
    @DisplayName("a corrupt EXIF segment does not fail the upload")
    void malformedExifIsToleratedRatherThanRejected() throws Exception {
        JsonNode seller = register(uniqueEmail("broken-exif"), UserRole.PARTICULIER);
        String listingId = createListing(seller);

        byte[] uploaded = jpegWithRawApp1(1000, 800, "Exif\0\0GARBAGE-NOT-A-TIFF-HEADER"
                .getBytes(StandardCharsets.US_ASCII));

        // A photograph with a damaged tag is still a usable photograph.
        String body = confirm(seller, listingId, requestAndPut(seller, listingId, uploaded));
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("READY");
    }

    // --- helpers ----------------------------------------------------------

    private String requestAndPut(JsonNode session, String listingId, byte[] content) throws Exception {
        String ticketBody = mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "photo.jpg",
                                "contentType", "image/jpeg",
                                "byteSize", content.length))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode ticket = objectMapper.readTree(ticketBody);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ticket.get("uploadUrl").asText()))
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);

        return ticket.get("imageId").asText();
    }

    private String confirm(JsonNode session, String listingId, String imageId) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/" + imageId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
    }

    /** Requests a slot, PUTs the bytes and confirms. Returns the image id. */
    private String uploadAndConfirm(JsonNode session, String listingId, byte[] content) throws Exception {
        String imageId = requestAndPut(session, listingId, content);
        confirm(session, listingId, imageId);
        return imageId;
    }

    private static byte[] lastBytes(byte[] content, int count) {
        int from = Math.max(0, content.length - count);
        return java.util.Arrays.copyOfRange(content, from, content.length);
    }

    private static boolean containsMarker(byte[] content) {
        return containsAscii(content, GPS_MARKER);
    }

    private static boolean containsAscii(byte[] content, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= content.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (content[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** A JPEG carrying a genuine EXIF APP1 segment: orientation tag plus a GPS-like string. */
    private static byte[] jpegWithExif(int width, int height, int orientation) throws Exception {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8});                 // big-endian, IFD at offset 8
        tiff.write(new byte[]{0, 1});                                        // one entry
        tiff.write(new byte[]{1, 0x12, 0, 3, 0, 0, 0, 1,
                (byte) ((orientation >> 8) & 0xFF), (byte) (orientation & 0xFF), 0, 0});
        tiff.write(new byte[]{0, 0, 0, 0});                                  // no next IFD
        tiff.write(GPS_MARKER.getBytes(StandardCharsets.US_ASCII));          // stands in for a GPS IFD

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write("Exif".getBytes(StandardCharsets.US_ASCII));
        app1.write(0);
        app1.write(0);
        app1.write(tiff.toByteArray());

        return jpegWithRawApp1(width, height, app1.toByteArray());
    }

    private static byte[] jpegWithRawApp1(int width, int height, byte[] payload) throws Exception {
        byte[] jpeg = plainJpeg(width, height);
        int length = payload.length + 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                       // SOI
        out.write(0xFF);
        out.write(0xE1);                             // APP1
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] plainJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int y = 0; y < height; y++) {
                graphics.setColor(new Color(30 + (y * 180 / height), 120, 190));
                graphics.drawLine(0, y, width, y);
            }
            graphics.setColor(Color.WHITE);
            graphics.fillOval(width / 10, height / 10, width / 3, height / 3);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", bytes);
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
}
