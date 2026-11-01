package com.autox.search.interpret;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Natural-language search. Public: describing a car needs no account, as searching for one
 * does not.
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Natural-language search",
        description = "A description of a car turned into search filters. Optional: see /capabilities. "
                + "No authentication.")
public class SearchInterpretController {

    /** Configuration changes only with a deployment; a few minutes of cache is harmless. */
    private static final CacheControl CAPABILITIES_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final SearchInterpretService service;

    public SearchInterpretController(SearchInterpretService service) {
        this.service = service;
    }

    /**
     * Which optional search features this server has. A client hides natural-language search
     * when {@code interpret} is false.
     */
    @GetMapping("/capabilities")
    public ResponseEntity<SearchCapabilities> capabilities() {
        return ResponseEntity.ok().cacheControl(CAPABILITIES_CACHE).body(service.capabilities());
    }

    /**
     * Turns a description such as "SUV diesel automatique moins de 15 000 €" into the filters
     * {@code GET /api/v1/listings} takes. Does not search: apply {@code criteria} and search as
     * usual.
     *
     * <p>Only the query is sent to the language model, and every value it returns is checked
     * against our enums, the catalogue and fixed bounds; what fails, and what the search cannot
     * filter on, is listed in {@code notUnderstood}.
     *
     * <p>404 when the feature is off. 429 {@code RATE_LIMITED} beyond ten requests in ten minutes
     * from one address. 503 {@code INTERPRETER_UNAVAILABLE} when the model times out (five
     * seconds), its quota is spent or it fails — then use the ordinary search.
     */
    @PostMapping("/interpret")
    public InterpretResponse interpret(@Valid @RequestBody InterpretRequest request, HttpServletRequest http) {
        return service.interpret(request.query(), http.getRemoteAddr());
    }
}
