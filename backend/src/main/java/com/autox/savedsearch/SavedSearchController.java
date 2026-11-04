package com.autox.savedsearch;

import com.autox.savedsearch.dto.CreateSavedSearchRequest;
import com.autox.savedsearch.dto.DigestTestResult;
import com.autox.savedsearch.dto.SavedSearchView;
import com.autox.savedsearch.dto.UnsubscribeRequest;
import com.autox.savedsearch.dto.UnsubscribeResult;
import com.autox.savedsearch.dto.UpdateSavedSearchRequest;
import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Saved searches: the signed-in account's own, the e-mail's unsubscribe link, and a test digest. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Saved searches", description = "Saved searches and their e-mail alerts.")
@RestController
public class SavedSearchController {

    private final SavedSearchService service;
    private final AlertDigest digest;

    public SavedSearchController(SavedSearchService service, AlertDigest digest) {
        this.service = service;
        this.digest = digest;
    }

    /**
     * Saves a search. Needs a verified address: 403 {@code EMAIL_NOT_VERIFIED}. At most ten per
     * account: 409 {@code SAVED_SEARCH_LIMIT_REACHED}. {@code criteria} is what
     * {@code GET /api/v1/listings} would be sent, {@code params} the results page's query string.
     */
    @PostMapping("/api/v1/saved-searches")
    public ResponseEntity<SavedSearchView> saveSearch(@AuthenticationPrincipal Jwt token,
                                                  @Valid @RequestBody CreateSavedSearchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(CurrentUser.id(token), request));
    }

    @GetMapping("/api/v1/saved-searches")
    public List<SavedSearchView> listSavedSearches(@AuthenticationPrincipal Jwt token) {
        return service.list(CurrentUser.id(token));
    }

    /** Renames, pauses or resumes. Resuming sends nothing published during the pause. */
    @PatchMapping("/api/v1/saved-searches/{id}")
    public SavedSearchView updateSavedSearch(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                                  @Valid @RequestBody UpdateSavedSearchRequest request) {
        return service.update(CurrentUser.id(token), id, request);
    }

    @DeleteMapping("/api/v1/saved-searches/{id}")
    public ResponseEntity<Void> deleteSavedSearch(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        service.delete(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }

    /** The search was opened from "Mes recherches": its count of new listings starts again. */
    @PostMapping("/api/v1/saved-searches/{id}/visit")
    public ResponseEntity<Void> visitSavedSearch(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        service.visit(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Turns off one search's alerts, from the link in an e-mail. No credentials: the signed token is
     * the authority. A POST, so that a mail scanner fetching the link's page changes nothing.
     * 400 {@code INVALID_UNSUBSCRIBE_TOKEN} if it was not signed here.
     */
    @PostMapping("/api/v1/saved-searches/unsubscribe")
    public UnsubscribeResult unsubscribeFromAlert(@Valid @RequestBody UnsubscribeRequest request) {
        return service.unsubscribe(request.token());
    }

    /**
     * Writes the calling administrator's own digest now, whatever the time and whether or not they
     * have had one today, for testing. Nobody else gets anything. It is a real digest: what it sends
     * is recorded as sent, and it goes out with the next poll.
     */
    @PostMapping("/api/v1/admin/alerts/send-mine")
    public DigestTestResult sendMyDigest(@AuthenticationPrincipal Jwt token) {
        return new DigestTestResult(digest.digestFor(CurrentUser.id(token), false));
    }
}
