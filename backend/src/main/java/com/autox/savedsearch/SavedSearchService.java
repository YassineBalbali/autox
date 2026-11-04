package com.autox.savedsearch;

import com.autox.api.error.ApiException;
import com.autox.savedsearch.dto.CreateSavedSearchRequest;
import com.autox.savedsearch.dto.SavedSearchView;
import com.autox.savedsearch.dto.UnsubscribeResult;
import com.autox.savedsearch.dto.UpdateSavedSearchRequest;
import com.autox.search.ListingSearchCriteria;
import com.autox.search.ListingSearchRepository;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Créer une alerte" and "Mes recherches". The filters are saved as the API takes them, the query
 * string beside them, and both come from one parsed search on the results page.
 *
 * <p>Saving needs a verified address, whenever the account was made: an alert is e-mail. An account
 * from before verification that keeps working otherwise still has to verify for this.
 */
@Service
public class SavedSearchService {

    /** Including the paused ones. */
    static final int MAX_PER_ACCOUNT = 10;

    /** "99+" on the page: the figure is there to say there is something new, not to be exact. */
    static final int NEW_COUNT_CAP = 99;

    private static final Pattern PARAM_KEY = Pattern.compile("[a-z_]{1,30}");
    private static final int MAX_PARAM_LENGTH = 300;
    private static final int MAX_PARAMS = 40;

    private final SavedSearchRepository repository;
    private final SavedSearchCriteriaCodec codec;
    private final ResolvedSearch.Resolver resolver;
    private final ListingSearchRepository search;
    private final AppUserRepository users;
    private final UnsubscribeToken tokens;
    private final ObjectMapper json;

    public SavedSearchService(SavedSearchRepository repository, SavedSearchCriteriaCodec codec,
                              ResolvedSearch.Resolver resolver, ListingSearchRepository search,
                              AppUserRepository users, UnsubscribeToken tokens, ObjectMapper json) {
        this.repository = repository;
        this.codec = codec;
        this.resolver = resolver;
        this.search = search;
        this.users = users;
        this.tokens = tokens;
        this.json = json;
    }

    @Transactional
    public SavedSearchView create(UUID userId, CreateSavedSearchRequest request) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId));
        if (!user.isEmailVerified()) {
            throw ApiException.emailNotVerified();
        }
        ListingSearchCriteria criteria = request.criteria();
        if (criteria.hasPartialBoundingBox()) {
            throw ApiException.validationFailed("A bounding box needs all four of latMin, latMax, lonMin and lonMax.");
        }
        Map<String, String> params = request.params() == null ? Map.of() : request.params();
        requireSafeParams(params);

        // Before the count, and held to the commit: two saves at once take turns.
        repository.lockAccount(userId);
        if (repository.countFor(userId) >= MAX_PER_ACCOUNT) {
            throw ApiException.savedSearchLimitReached(MAX_PER_ACCOUNT);
        }

        String makeLabel = criteria.makeId() == null ? null : repository.makeName(criteria.makeId())
                .orElseThrow(() -> ApiException.validationFailed("Unknown makeId."));
        String modelLabel = criteria.modelId() == null ? null : repository.modelName(criteria.modelId())
                .orElseThrow(() -> ApiException.validationFailed("Unknown modelId."));

        SavedSearchRepository.Row row = repository.insert(userId, request.name().strip(),
                SavedSearchCriteriaCodec.CURRENT_VERSION, codec.write(criteria), write(params), makeLabel, modelLabel);
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<SavedSearchView> list(UUID userId) {
        return repository.listFor(userId).stream().map(this::view).toList();
    }

    @Transactional
    public SavedSearchView update(UUID userId, UUID id, UpdateSavedSearchRequest request) {
        SavedSearchRepository.Row row = owned(userId, id);
        if (request.name() != null) {
            repository.rename(id, request.name().strip());
        }
        if (Boolean.TRUE.equals(request.paused())) {
            repository.pause(id);
        } else if (Boolean.FALSE.equals(request.paused())) {
            repository.resume(id);
        }
        return view(repository.find(row.id()).orElseThrow());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(owned(userId, id).id());
    }

    /** The search has been opened from the page: "new" counts from now. */
    @Transactional
    public void visit(UUID userId, UUID id) {
        repository.markVisited(owned(userId, id).id());
    }

    /**
     * From an e-mail's link, with no sign-in. Pauses the search rather than deleting it, so a click
     * by mistake loses nothing. A search deleted since is not an error: the link has done its job.
     *
     * @throws ApiException 400 if the token was not signed here
     */
    @Transactional
    public UnsubscribeResult unsubscribe(String token) {
        UUID id = tokens.verify(token).orElseThrow(ApiException::invalidUnsubscribeToken);
        return repository.find(id).map(row -> {
            repository.pause(id);
            return new UnsubscribeResult(row.name());
        }).orElseGet(() -> new UnsubscribeResult(null));
    }

    private SavedSearchRepository.Row owned(UUID userId, UUID id) {
        // Someone else's answers 404, like a conversation: whether it exists is not theirs to learn.
        return repository.find(id).filter(row -> row.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Saved search " + id));
    }

    private SavedSearchView view(SavedSearchRepository.Row row) {
        ResolvedSearch resolved = resolver.resolve(row);
        int fresh = !resolved.readable() ? 0
                : search.countPublishedSince(resolved.criteria(), row.lastVisitedAt(), row.userId(), null, NEW_COUNT_CAP);
        return new SavedSearchView(row.id(), row.name(), row.pausedAt() != null, resolved.linkParams(),
                Math.min(fresh, NEW_COUNT_CAP), fresh > NEW_COUNT_CAP, resolved.readable(),
                resolved.missingMake(), resolved.missingModel(), row.createdAt());
    }

    /** They only ever go back into a link to the user's own results page, but not unchecked. */
    private static void requireSafeParams(Map<String, String> params) {
        if (params.size() > MAX_PARAMS || params.entrySet().stream().anyMatch(entry ->
                !PARAM_KEY.matcher(entry.getKey()).matches()
                        || entry.getValue() == null || entry.getValue().length() > MAX_PARAM_LENGTH)) {
            throw ApiException.validationFailed("params is not a search's query string.");
        }
    }

    private String write(Map<String, String> params) {
        try {
            return json.writeValueAsString(params);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Link parameters could not be written", impossible);
        }
    }
}
