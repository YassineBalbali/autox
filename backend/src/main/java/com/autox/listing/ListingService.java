package com.autox.listing;

import com.autox.api.error.ApiException;
import com.autox.catalog.VehicleFeature;
import com.autox.catalog.VehicleFeatureRepository;
import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.catalog.VehicleModel;
import com.autox.catalog.VehicleModelRepository;
import com.autox.catalog.VehicleTrim;
import com.autox.catalog.VehicleTrimRepository;
import com.autox.listing.dto.CreateListingRequest;
import com.autox.listing.dto.ListingResponse;
import com.autox.listing.dto.ListingSummary;
import com.autox.listing.dto.UpdateListingRequest;
import com.autox.reference.Country;
import com.autox.reference.CountryRepository;
import com.autox.reference.Currency;
import com.autox.reference.CurrencyRepository;
import com.autox.reference.GeoPlace;
import com.autox.reference.GeoPlaceRepository;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing lifecycle.
 *
 * <p>Three rules are enforced on every path, and none of them is left to the controller:
 * <ul>
 *   <li><b>Ownership.</b> A caller touches only their own listings; an ADMIN touches any.
 *       Checked after loading, and a listing someone else owns is reported as 404 rather
 *       than 403 — a 403 confirms the id exists, which is a probing oracle.</li>
 *   <li><b>Transitions.</b> Every status change goes through {@link ListingStatus}'s
 *       table. An illegal move is a 409 naming the states that would have been legal.</li>
 *   <li><b>Quota.</b> The active-listing cap is checked at the moment of publication,
 *       against the seller's role and any per-account override.</li>
 * </ul>
 */
@Service
public class ListingService {

    private static final Logger log = LoggerFactory.getLogger(ListingService.class);

    private final ListingRepository listings;
    private final ListingPriceHistoryRepository priceHistory;
    private final AppUserRepository users;
    private final CurrencyRepository currencies;
    private final CountryRepository countries;
    private final GeoPlaceRepository geoPlaces;
    private final VehicleMakeRepository makes;
    private final VehicleModelRepository models;
    private final VehicleTrimRepository trims;
    private final VehicleFeatureRepository features;
    private final ListingProperties properties;
    private final ListingAccessGuard accessGuard;
    private final ListingImageRepository images;
    private final com.autox.storage.ObjectStorage storage;

    public ListingService(ListingRepository listings,
                          ListingPriceHistoryRepository priceHistory,
                          AppUserRepository users,
                          CurrencyRepository currencies,
                          CountryRepository countries,
                          GeoPlaceRepository geoPlaces,
                          VehicleMakeRepository makes,
                          VehicleModelRepository models,
                          VehicleTrimRepository trims,
                          VehicleFeatureRepository features,
                          ListingProperties properties,
                          ListingAccessGuard accessGuard,
                          ListingImageRepository images,
                          com.autox.storage.ObjectStorage storage) {
        this.listings = listings;
        this.priceHistory = priceHistory;
        this.users = users;
        this.currencies = currencies;
        this.countries = countries;
        this.geoPlaces = geoPlaces;
        this.makes = makes;
        this.models = models;
        this.trims = trims;
        this.features = features;
        this.properties = properties;
        this.accessGuard = accessGuard;
        this.images = images;
        this.storage = storage;
    }

    /**
     * Builds the response, attaching the gallery. Every write path returns through here so a
     * client never has to make a second call to find out what the listing now looks like.
     */
    private ListingResponse respond(Listing listing) {
        List<com.autox.listing.dto.ImageResponse> gallery = images.findReadyByListing(listing.getId()).stream()
                .map(image -> com.autox.listing.dto.ImageResponse.from(image, storage::readUrl))
                .toList();
        return ListingResponse.from(listing, gallery);
    }

    // --- reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public ListingResponse get(UUID callerId, boolean admin, UUID listingId) {
        return respond(loadAccessible(callerId, admin, listingId));
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> listOwn(UUID callerId, ListingStatus status, Pageable pageable) {
        Page<Listing> page = status == null
                ? listings.findBySeller(callerId, pageable)
                : listings.findBySellerAndStatus(callerId, status, pageable);
        return page.map(ListingSummary::from);
    }

    // --- writes -----------------------------------------------------------

    @Transactional
    public ListingResponse create(UUID callerId, CreateListingRequest request) {
        AppUser seller = users.findActiveById(callerId).orElseThrow(() -> ApiException.notFound("User"));

        Listing listing = new Listing();
        listing.setSeller(seller);
        listing.setStatus(ListingStatus.DRAFT);
        listing.setModerationStatus(ModerationStatus.NOT_REVIEWED);

        listing.setTitle(request.title().trim());
        listing.setDescription(trimToNull(request.description()));
        listing.setPriceAmount(request.priceAmount());
        listing.setPriceCurrency(requireCurrency(request.currencyCode()));
        listing.setPriceNegotiable(Boolean.TRUE.equals(request.priceNegotiable()));
        listing.setVatDeductible(request.vatDeductible());
        listing.setHideExactLocation(request.hideExactLocation() == null || request.hideExactLocation());

        applyLocation(listing, request.geoPlaceId(), request.postalCode(), request.city(), request.countryCode());
        applyVehicleIdentity(listing, request.makeId(), request.modelId(), request.trimId(),
                request.makeText(), request.modelText(), request.versionText());

        listing.setFirstRegistrationYear(request.firstRegistrationYear().shortValue());
        listing.setFirstRegistrationMonth(toShort(request.firstRegistrationMonth()));
        listing.setMileageKm(request.mileageKm());
        listing.setFuelType(request.fuelType());
        listing.setGearbox(request.gearbox());
        listing.setBodyType(request.bodyType());
        listing.setDrivetrain(request.drivetrain());
        listing.setPowerHp(toShort(request.powerHp()));
        listing.setPowerKw(toShort(request.powerKw()));
        listing.setEngineDisplacementCc(request.engineDisplacementCc());
        listing.setDoors(toShort(request.doors()));
        listing.setSeats(toShort(request.seats()));
        listing.setVehicleCondition(request.condition() == null ? VehicleCondition.USED : request.condition());
        listing.setExteriorColor(request.exteriorColor());
        listing.setColorText(trimToNull(request.colorText()));
        listing.setMetallicPaint(request.metallicPaint());
        listing.setInteriorMaterial(request.interiorMaterial());
        listing.setOwnerCount(toShort(request.ownerCount()));
        listing.setServiceHistory(request.serviceHistory());
        listing.setWarrantyMonths(toShort(request.warrantyMonths()));
        listing.setTechnicalInspectionValidUntil(request.technicalInspectionValidUntil());
        listing.setCo2EmissionsGKm(toShort(request.co2EmissionsGKm()));
        listing.setEmissionClass(request.emissionClass());
        listing.setVin(normalizeVin(request.vin()));

        if (request.featureCodes() != null) {
            replaceFeatures(listing, request.featureCodes());
        }

        listings.save(listing);
        log.info("Listing {} created as DRAFT by {}", listing.getId(), callerId);
        return respond(listing);
    }

    @Transactional
    public ListingResponse update(UUID callerId, boolean admin, UUID listingId, UpdateListingRequest request) {
        Listing listing = loadAccessible(callerId, admin, listingId);

        if (!listing.getStatus().isEditable()) {
            throw ApiException.conflict("A listing in state " + listing.getStatus()
                    + " can no longer be edited.");
        }

        // Read before anything is written: the price history needs what the listing was
        // asking a moment ago, and by the end of this method that value is gone.
        long priceBefore = listing.getPriceAmount();
        Currency currencyBefore = listing.getPriceCurrency();

        if (request.title() != null) {
            listing.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            listing.setDescription(blankToNull(request.description()));
        }
        if (request.priceAmount() != null) {
            listing.setPriceAmount(request.priceAmount());
        }
        if (request.currencyCode() != null) {
            listing.setPriceCurrency(requireCurrency(request.currencyCode()));
        }
        if (request.priceNegotiable() != null) {
            listing.setPriceNegotiable(request.priceNegotiable());
        }
        if (request.vatDeductible() != null) {
            listing.setVatDeductible(request.vatDeductible());
        }
        if (request.hideExactLocation() != null) {
            listing.setHideExactLocation(request.hideExactLocation());
        }

        if (request.geoPlaceId() != null || request.postalCode() != null || request.city() != null) {
            applyLocation(listing, request.geoPlaceId(),
                    request.postalCode() != null ? request.postalCode() : listing.getPostalCode(),
                    request.city() != null ? request.city() : listing.getCity(),
                    request.countryCode() != null ? request.countryCode() : listing.getCountry().getCode());
        }

        if (request.makeId() != null || request.makeText() != null
                || request.modelId() != null || request.modelText() != null || request.trimId() != null) {
            applyVehicleIdentity(listing,
                    request.makeId(), request.modelId(), request.trimId(),
                    request.makeText(), request.modelText(),
                    request.versionText() != null ? request.versionText() : listing.getVersionText());
        } else if (request.versionText() != null) {
            listing.setVersionText(blankToNull(request.versionText()));
        }

        if (request.firstRegistrationYear() != null) {
            listing.setFirstRegistrationYear(request.firstRegistrationYear().shortValue());
        }
        if (request.firstRegistrationMonth() != null) {
            listing.setFirstRegistrationMonth(toShort(request.firstRegistrationMonth()));
        }
        if (request.mileageKm() != null) {
            listing.setMileageKm(request.mileageKm());
        }
        if (request.fuelType() != null) {
            listing.setFuelType(request.fuelType());
        }
        if (request.gearbox() != null) {
            listing.setGearbox(request.gearbox());
        }
        if (request.bodyType() != null) {
            listing.setBodyType(request.bodyType());
        }
        if (request.drivetrain() != null) {
            listing.setDrivetrain(request.drivetrain());
        }
        if (request.powerHp() != null) {
            listing.setPowerHp(toShort(request.powerHp()));
        }
        if (request.powerKw() != null) {
            listing.setPowerKw(toShort(request.powerKw()));
        }
        if (request.engineDisplacementCc() != null) {
            listing.setEngineDisplacementCc(request.engineDisplacementCc());
        }
        if (request.doors() != null) {
            listing.setDoors(toShort(request.doors()));
        }
        if (request.seats() != null) {
            listing.setSeats(toShort(request.seats()));
        }
        if (request.condition() != null) {
            listing.setVehicleCondition(request.condition());
        }
        if (request.exteriorColor() != null) {
            listing.setExteriorColor(request.exteriorColor());
        }
        if (request.colorText() != null) {
            listing.setColorText(blankToNull(request.colorText()));
        }
        if (request.metallicPaint() != null) {
            listing.setMetallicPaint(request.metallicPaint());
        }
        if (request.interiorMaterial() != null) {
            listing.setInteriorMaterial(request.interiorMaterial());
        }
        if (request.ownerCount() != null) {
            listing.setOwnerCount(toShort(request.ownerCount()));
        }
        if (request.serviceHistory() != null) {
            listing.setServiceHistory(request.serviceHistory());
        }
        if (request.warrantyMonths() != null) {
            listing.setWarrantyMonths(toShort(request.warrantyMonths()));
        }
        if (request.technicalInspectionValidUntil() != null) {
            listing.setTechnicalInspectionValidUntil(request.technicalInspectionValidUntil());
        }
        if (request.co2EmissionsGKm() != null) {
            listing.setCo2EmissionsGKm(toShort(request.co2EmissionsGKm()));
        }
        if (request.emissionClass() != null) {
            listing.setEmissionClass(request.emissionClass());
        }
        if (request.vin() != null) {
            listing.setVin(normalizeVin(blankToNull(request.vin())));
        }
        if (request.featureCodes() != null) {
            replaceFeatures(listing, request.featureCodes());
        }

        recordPriceChange(listing, priceBefore, currencyBefore);
        return respond(listing);
    }

    /**
     * Makes a listing live. First publication stamps {@code publishedAt}; every
     * publication resets the expiry window.
     */
    @Transactional
    public ListingResponse publish(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);
        requireTransition(listing, ListingStatus.ACTIVE);
        requirePhoto(listing);
        requireQuotaAvailable(listing);

        Instant now = Instant.now();
        boolean firstPublication = listing.getPublishedAt() == null;
        if (firstPublication) {
            listing.setPublishedAt(now);
        }
        listing.setExpiresAt(now.plus(properties.expiry()));
        listing.setStatus(ListingStatus.ACTIVE);

        if (firstPublication) {
            // The price the market first saw. Everything before this was a draft.
            priceHistory.save(ListingPriceHistory.of(listing, PriceChangeReason.INITIAL));
        }

        log.info("Listing {} published until {}", listing.getId(), listing.getExpiresAt());
        return respond(listing);
    }

    /**
     * Records that the vehicle sold. The seller only — an admin may remove a listing but
     * cannot assert an outcome on someone's behalf.
     *
     * <p>This exists so that a sale is recorded as a sale. Without it sellers delete the
     * listing instead, and the outcome — what actually changed hands, at what price — is
     * exactly the data that makes price estimation possible later.
     *
     * <p>A sold listing stays readable by its owner and leaves public search by virtue of
     * no longer being ACTIVE, which is the condition every search index is built on.
     */
    @Transactional
    public ListingResponse markSold(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);

        if (!listing.getSeller().getId().equals(callerId)) {
            // The caller is an admin: they can see it, so 403 reveals nothing new.
            throw ApiException.forbidden("Only the seller can mark a listing as sold.");
        }

        requireTransition(listing, ListingStatus.SOLD);
        listing.setStatus(ListingStatus.SOLD);
        listing.setSoldAt(Instant.now());

        log.info("Listing {} marked sold", listing.getId());
        return respond(listing);
    }

    /** Hides a live listing without ending it. Frees the seller's quota slot. */
    @Transactional
    public ListingResponse unpublish(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);
        requireTransition(listing, ListingStatus.PAUSED);
        listing.setStatus(ListingStatus.PAUSED);
        return respond(listing);
    }

    /**
     * Extends the expiry window by another full period. An expired listing is returned to
     * ACTIVE, which re-applies the quota check — the slot may have been taken meanwhile.
     */
    @Transactional
    public ListingResponse renew(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);

        if (listing.getStatus() != ListingStatus.ACTIVE && listing.getStatus() != ListingStatus.EXPIRED) {
            throw ApiException.conflict("Only an active or expired listing can be renewed; this one is "
                    + listing.getStatus() + ".");
        }

        if (listing.getStatus() == ListingStatus.EXPIRED) {
            requireTransition(listing, ListingStatus.ACTIVE);
            requireQuotaAvailable(listing);
            listing.setStatus(ListingStatus.ACTIVE);
        }

        // Measured from now, not from the old expiry, so renewing early is not punished
        // and renewing late does not back-date the window.
        listing.setExpiresAt(Instant.now().plus(properties.expiry()));
        return respond(listing);
    }

    /**
     * Soft delete. The row survives because the moderation trail and any dispute refer to
     * it; it simply stops existing for every read path.
     */
    @Transactional
    public void delete(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);

        Instant now = Instant.now();
        if (listing.getStatus() != ListingStatus.REMOVED) {
            requireTransition(listing, ListingStatus.REMOVED);
            listing.setStatus(ListingStatus.REMOVED);
        }
        listing.setDeletedAt(now);

        log.info("Listing {} soft-deleted by {}", listing.getId(), callerId);
    }

    /** Called by the sweeper. Returns how many listings fell due. */
    @Transactional
    public int expireDueListings() {
        return listings.expireDueListings(Instant.now());
    }

    // --- rules ------------------------------------------------------------

    private Listing loadAccessible(UUID callerId, boolean admin, UUID listingId) {
        return accessGuard.require(callerId, admin, listingId);
    }

    private void requireTransition(Listing listing, ListingStatus target) {
        ListingStatus current = listing.getStatus();
        if (current == target) {
            throw ApiException.conflict("Listing is already " + current + ".");
        }
        if (!current.canTransitionTo(target)) {
            throw ApiException.conflict("Cannot move a listing from " + current + " to " + target
                    + ". Allowed from " + current + ": " + current.allowedTargets() + ".");
        }
    }

    /**
     * A listing with no photograph is close to worthless to a buyer and drags down every
     * search result page it appears on. Checked only at publication, so a draft can be
     * written before the photos are ready.
     */
    private void requirePhoto(Listing listing) {
        if (images.findReadyByListing(listing.getId()).isEmpty()) {
            throw ApiException.photoRequired(
                    "Add at least one photo before publishing. Uploads that have not been confirmed do not count.");
        }
    }

    /**
     * The cap a private individual is held to, as the brief requires. Zero means
     * unlimited; a per-account override on the user wins over the role default.
     *
     * <p>Counting and then publishing is not safe on its own. At READ COMMITTED two
     * concurrent publications by the same seller both read one below the limit, both pass,
     * and both commit — which is how a cap of five becomes six. A row lock on the seller
     * makes the check and the write atomic <em>for that seller</em>: the second request
     * waits here, and then counts the listing the first one created.
     *
     * <p>Locking the seller rather than taking a table lock or raising the isolation level
     * keeps the contention where it belongs. Two different sellers never wait for each
     * other, and one seller publishing twice at once is not a case worth optimising.
     */
    private void requireQuotaAvailable(Listing listing) {
        AppUser seller = listing.getSeller();
        int limit = effectiveLimit(seller);
        if (limit <= 0) {
            return;
        }

        users.lockForQuotaCheck(seller.getId());
        long active = listings.countActiveBySeller(seller.getId());
        // The listing being published is not ACTIVE yet, so it is not in that count.
        if (active >= limit) {
            throw ApiException.quotaExceeded(
                    "You already have " + active + " active listings, which is the maximum of " + limit
                            + " for this account. Pause or remove one first.");
        }
    }

    /**
     * Appends to the price series, if the price actually moved.
     *
     * <p>Only for a listing that has been published. Changing the price of a draft is
     * composition, not a market signal: nobody saw the old number, and recording it would
     * fill the series that price estimation will learn from with noise.
     *
     * <p>A currency change counts, because an amount without its currency says nothing.
     */
    private void recordPriceChange(Listing listing, long previousAmount, Currency previousCurrency) {
        if (listing.getPublishedAt() == null) {
            return;
        }
        boolean moved = listing.getPriceAmount() != previousAmount
                || !listing.getPriceCurrency().getCode().equals(previousCurrency.getCode());
        if (!moved) {
            return;
        }

        priceHistory.save(ListingPriceHistory.of(listing, PriceChangeReason.SELLER_EDIT));
        log.info("Listing {} price changed from {} {} to {} {}", listing.getId(),
                previousAmount, previousCurrency.getCode(),
                listing.getPriceAmount(), listing.getPriceCurrency().getCode());
    }

    private int effectiveLimit(AppUser seller) {
        if (seller.getActiveListingLimit() != null) {
            return seller.getActiveListingLimit();
        }
        return seller.getRole() == UserRole.PARTICULIER
                ? properties.particulierActiveLimit()
                : properties.professionnelActiveLimit();
    }

    // --- field mapping ----------------------------------------------------

    private void applyLocation(Listing listing, UUID geoPlaceId, String postalCode, String city, String countryCode) {
        if (geoPlaceId != null) {
            GeoPlace place = geoPlaces.findById(geoPlaceId)
                    .orElseThrow(() -> ApiException.validationFailed("geoPlaceId does not match a known place."));
            listing.setGeoPlace(place);
            listing.setCountry(place.getCountry());
            // Denormalised so radius search needs no join.
            listing.setLatitude(place.getLatitude());
            listing.setLongitude(place.getLongitude());
            listing.setPostalCode(null);
            listing.setCity(null);
            return;
        }

        Country country = requireCountry(countryCode);
        listing.setGeoPlace(null);
        listing.setCountry(country);
        listing.setPostalCode(requireText(postalCode, "postalCode"));
        listing.setCity(requireText(city, "city"));
        // Unknown until the gazetteer can resolve this postal code.
        listing.setLatitude(null);
        listing.setLongitude(null);
    }

    /**
     * Catalogue reference or free text, never a mixture. The composite foreign keys in the
     * schema would catch an incoherent pair anyway; failing here turns a 500 from the
     * driver into a 400 that names the problem.
     */
    private void applyVehicleIdentity(Listing listing, UUID makeId, UUID modelId, UUID trimId,
                                      String makeText, String modelText, String versionText) {
        if (makeId != null) {
            VehicleMake make = makes.findActiveById(makeId)
                    .orElseThrow(() -> ApiException.validationFailed("makeId does not match an active make."));
            VehicleModel model = models.findActiveById(modelId)
                    .orElseThrow(() -> ApiException.validationFailed("modelId does not match an active model."));
            if (!model.getMake().getId().equals(make.getId())) {
                throw ApiException.validationFailed("modelId does not belong to makeId.");
            }

            VehicleTrim trim = null;
            if (trimId != null) {
                trim = trims.findActiveById(trimId)
                        .orElseThrow(() -> ApiException.validationFailed("trimId does not match an active trim."));
                if (!trim.getModel().getId().equals(model.getId())) {
                    throw ApiException.validationFailed("trimId does not belong to modelId.");
                }
            }

            listing.setMake(make);
            listing.setModel(model);
            listing.setTrim(trim);
            listing.setMakeText(null);
            listing.setModelText(null);
        } else {
            listing.setMake(null);
            listing.setModel(null);
            listing.setTrim(null);
            listing.setMakeText(requireText(makeText, "makeText"));
            listing.setModelText(requireText(modelText, "modelText"));
        }
        listing.setVersionText(blankToNull(versionText == null ? "" : versionText));
    }

    private void replaceFeatures(Listing listing, Set<String> codes) {
        listing.getFeatures().clear();
        if (codes.isEmpty()) {
            return;
        }
        Set<String> wanted = codes.stream().map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        List<VehicleFeature> found = features.findActiveByCodes(wanted);
        if (found.size() != wanted.size()) {
            Set<String> known = found.stream().map(VehicleFeature::getCode).collect(java.util.stream.Collectors.toSet());
            wanted.removeAll(known);
            throw ApiException.validationFailed("Unknown feature codes: " + wanted + ".");
        }
        listing.getFeatures().addAll(found);
    }

    private Currency requireCurrency(String code) {
        return currencies.findById(code)
                .orElseThrow(() -> ApiException.validationFailed(
                        "currencyCode '" + code + "' is not a supported currency."));
    }

    private Country requireCountry(String code) {
        return countries.findEnabledByCode(code)
                .orElseThrow(() -> ApiException.validationFailed(
                        "countryCode '" + code + "' is not an enabled market."));
    }

    private static String requireText(String value, String field) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw ApiException.validationFailed(field + " is required.");
        }
        return trimmed;
    }

    private static String normalizeVin(String vin) {
        return vin == null ? null : vin.trim().toUpperCase(Locale.ROOT);
    }

    private static Short toShort(Integer value) {
        return value == null ? null : value.shortValue();
    }

    private static String trimToNull(String value) {
        return value == null ? null : blankToNull(value);
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
