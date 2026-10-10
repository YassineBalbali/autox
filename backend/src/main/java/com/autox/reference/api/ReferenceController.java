package com.autox.reference.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vocabularies a client builds its UI from. Public, unauthenticated and cacheable:
 * this is the same data for every caller and it changes only when a migration runs.
 *
 * <p>Every response carries {@code Cache-Control: public} and an {@code ETag}
 * ({@code ShallowEtagHeaderFilter}, registered in {@code WebConfig}), so a client that
 * already holds the current copy gets a 304 and no body.
 */
@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Reference", description = "Enum values, equipment vocabulary and the vehicle catalogue")
public class ReferenceController {

    /**
     * One hour. Long enough that the Angular app loads these once a session rather than once a
     * page, short enough that seeding the catalogue becomes visible without a cache purge.
     * A deploy changes the ETag, so a client never holds a stale copy past its max-age anyway.
     */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final ReferenceService reference;

    public ReferenceController(ReferenceService reference) {
        this.reference = reference;
    }

    /**
     * Every enum value the API may return or accept, keyed by type name.
     *
     * <p>Values only. Display labels belong in the client's own translation files; what a
     * client must not duplicate is the <em>set</em> of constants.
     */
    @GetMapping("/enums")
    public ResponseEntity<ReferenceDtos.Enums> enums() {
        return cacheable(reference.enums());
    }

    /** The equipment vocabulary: code, category and display order, with the seeded labels. */
    @GetMapping("/features")
    public ResponseEntity<List<ReferenceDtos.Feature>> features() {
        return cacheable(reference.features());
    }

    /**
     * Vehicle manufacturers.
     *
     * <p>Empty until the catalogue is seeded; listings identify their vehicle by free text in
     * the meantime, and the search endpoint accepts a name as well as an id for that reason.
     */
    @GetMapping("/makes")
    public ResponseEntity<List<ReferenceDtos.Make>> makes() {
        return cacheable(reference.makes());
    }

    /** The model lines of one manufacturer. 404 if the make is unknown. */
    @GetMapping("/makes/{makeId}/models")
    public ResponseEntity<List<ReferenceDtos.Model>> models(@PathVariable UUID makeId) {
        return cacheable(reference.models(makeId));
    }

    /**
     * The open markets: default locale, currency and the currency's minor-unit scale, which a
     * client needs in order to render the integer prices this API returns.
     */
    @GetMapping("/countries")
    public ResponseEntity<List<ReferenceDtos.Country>> countries() {
        return cacheable(reference.countries());
    }

    private static <T> ResponseEntity<T> cacheable(T body) {
        return ResponseEntity.ok().cacheControl(CACHE).body(body);
    }
}
