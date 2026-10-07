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
    private final AppUserRepository users;
    private final CurrencyRepository currencies;
    private final CountryRepository countries;
    private final GeoPlaceRepository geoPlaces;
    private final VehicleMakeRepository makes;
    private final VehicleModelRepository models;
    private final VehicleTrimRepository trims;
    private final VehicleFeatureRepository features;
    private final ListingProperties properties;

    public ListingService(ListingRepository listings,
                          AppUserRepository users,
                          CurrencyRepository currencies,
                          CountryRepository countries,
                          GeoPlaceRepository geoPlaces,
                          VehicleMakeRepository makes,
                          VehicleModelRepository models,
                          VehicleTrimRepository trims,
                          VehicleFeatureRepository features,
                          ListingProperties properties) {
        this.listings = listings;
        this.users = users;
        this.currencies = currencies;
        this.countries = countries;
        this.geoPlaces = geoPlaces;
        this.makes = makes;
        this.models = models;
        this.trims = trims;
        this.features = features;
        this.properties = properties;
    }

    // --- reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public ListingResponse get(UUID callerId, boolean admin, UUID listingId) {
        return ListingResponse.from(loadAccessible(callerId, admin, listingId));
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
        return ListingResponse.from(listing);
    }

    @Transactional
    public ListingResponse update(UUID callerId, boolean admin, UUID listingId, UpdateListingRequest request) {
        Listing listing = loadAccessible(callerId, admin, listingId);

        if (!listing.getStatus().isEditable()) {
            throw ApiException.conflict("A listing in state " + listing.getStatus()
                    + " can no longer be edited.");
        }

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

        return ListingResponse.from(listing);
    }

    /**
     * Makes a listing live. First publication stamps {@code publishedAt}; every
     * publication resets the expiry window.
     */
    @Transactional
    public ListingResponse publish(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);
        requireTransition(listing, ListingStatus.ACTIVE);
        requireQuotaAvailable(listing);

        Instant now = Instant.now();
        if (listing.getPublishedAt() == null) {
            listing.setPublishedAt(now);
        }
        listing.setExpiresAt(now.plus(properties.expiry()));
        listing.setStatus(ListingStatus.ACTIVE);

        log.info("Listing {} published until {}", listing.getId(), listing.getExpiresAt());
        return ListingResponse.from(listing);
    }

    /** Hides a live listing without ending it. Frees the seller's quota slot. */
    @Transactional
    public ListingResponse unpublish(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = loadAccessible(callerId, admin, listingId);
        requireTransition(listing, ListingStatus.PAUSED);
        listing.setStatus(ListingStatus.PAUSED);
        return ListingResponse.from(listing);
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
        return ListingResponse.from(listing);
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
        Listing listing = listings.findActiveById(listingId)
                .orElseThrow(() -> ApiException.notFound("Listing"));

        if (!admin && !listing.getSeller().getId().equals(callerId)) {
            // Not 403: distinguishing "exists but is not yours" from "does not exist"
            // lets anyone enumerate listing ids.
            throw ApiException.notFound("Listing");
        }
        return listing;
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
     * The cap a private individual is held to, as the brief requires. Zero means
     * unlimited; a per-account override on the user wins over the role default.
     */
    private void requireQuotaAvailable(Listing listing) {
        AppUser seller = listing.getSeller();
        int limit = effectiveLimit(seller);
        if (limit <= 0) {
            return;
        }

        long active = listings.countActiveBySeller(seller.getId());
        // The listing being published is not ACTIVE yet, so it is not in that count.
        if (active >= limit) {
            throw ApiException.quotaExceeded(
                    "You already have " + active + " active listings, which is the maximum of " + limit
                            + " for this account. Pause or remove one first.");
        }
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
