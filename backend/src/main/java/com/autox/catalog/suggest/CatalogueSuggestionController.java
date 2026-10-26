package com.autox.catalog.suggest;

import com.autox.api.error.ApiException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Makes and models to suggest as the visitor types in the search bar. Public, and
 * rate-limited per address like the rest of the public read API: it is called on keystrokes.
 *
 * <p>Not under {@code /reference}: those are vocabularies, the same until a migration runs
 * and cached for an hour. These follow the listings — an entry appears with its first
 * listing online and goes with its last — so they are cached for a minute.
 */
@RestController
@RequestMapping("/api/v1/catalogue")
@Tag(name = "Catalogue", description = "Make and model suggestions for the search bar. No authentication.")
public class CatalogueSuggestionController {

    /** Below two characters nearly every make matches, which suggests nothing. */
    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 60;
    static final int MAX_RESULTS = 8;

    /**
     * Short: a count may change with each listing published, and a visitor backspacing over
     * "peu" asks for the same prefix twice in a few seconds — the second time from the cache.
     */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();

    private final CatalogueSuggestionRepository suggestions;

    public CatalogueSuggestionController(CatalogueSuggestionRepository suggestions) {
        this.suggestions = suggestions;
    }

    /**
     * Up to eight makes and models whose name starts with {@code q}, ignoring case and
     * accents: "citro" finds Citroën, "a3" the A3, "audi a" the Audi A1, A3, A4… Makes come
     * first, then the most listed. Only entries with a listing online are returned; none is
     * an empty list. 400 if {@code q} is shorter than two characters or longer than sixty.
     */
    @GetMapping("/suggestions")
    public ResponseEntity<List<CatalogueSuggestion>> suggestions(
            @Parameter(description = "What has been typed: 2 to 60 characters.", example = "audi a")
            @RequestParam String q) {
        String typed = q.strip().replaceAll("\\s+", " ");
        if (typed.length() < MIN_LENGTH || typed.length() > MAX_LENGTH) {
            throw ApiException.validationFailed(
                    "q must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters, not counting outer spaces.");
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(suggestions.find(typed, MAX_RESULTS));
    }
}
