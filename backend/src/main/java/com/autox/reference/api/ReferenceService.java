package com.autox.reference.api;

import com.autox.api.error.ApiException;
import com.autox.api.error.ErrorCode;
import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FeatureCategory;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.catalog.VehicleFeatureRepository;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.catalog.VehicleModelRepository;
import com.autox.listing.EmissionClass;
import com.autox.listing.ExteriorColor;
import com.autox.listing.ImageStatus;
import com.autox.listing.ImageVariantType;
import com.autox.listing.InteriorMaterial;
import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationStatus;
import com.autox.listing.VehicleCondition;
import com.autox.reference.CountryRepository;
import com.autox.reference.DistanceUnit;
import com.autox.search.SearchSort;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vocabularies a client needs to build its own UI: enum values, the equipment list and
 * the vehicle catalogue.
 *
 * <p>These exist so nothing has to be duplicated in the frontend. A hardcoded copy of the
 * feature codes drifts the first time a migration adds one, and the failure is silent — the
 * new equipment simply never appears in the form.
 */
@Service
@Transactional(readOnly = true)
public class ReferenceService {

    /**
     * Every enum the API returns or accepts, under the name it is documented as.
     *
     * <p>Listed explicitly rather than discovered by classpath scanning. Scanning would mean
     * a client's dropdown silently gains values the moment someone adds an internal enum, and
     * this map is a published contract: what is in it is promised, what is not is private.
     */
    private static final Map<String, Class<? extends Enum<?>>> PUBLISHED_ENUMS = publishedEnums();

    private final VehicleFeatureRepository features;
    private final VehicleMakeRepository makes;
    private final VehicleModelRepository models;
    private final CountryRepository countries;

    public ReferenceService(VehicleFeatureRepository features, VehicleMakeRepository makes,
                            VehicleModelRepository models, CountryRepository countries) {
        this.features = features;
        this.makes = makes;
        this.models = models;
        this.countries = countries;
    }

    public ReferenceDtos.Enums enums() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        PUBLISHED_ENUMS.forEach((name, type) -> values.put(name,
                Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        return new ReferenceDtos.Enums(values);
    }

    public List<ReferenceDtos.Feature> features() {
        return features.findAllActiveWithTranslations().stream()
                .map(feature -> new ReferenceDtos.Feature(
                        feature.getCode(),
                        feature.getCategory(),
                        feature.getDisplayOrder(),
                        feature.getTranslations().stream()
                                .collect(Collectors.toMap(
                                        translation -> translation.getKey().getLocale(),
                                        translation -> translation.getLabel(),
                                        (first, second) -> first,
                                        LinkedHashMap::new))))
                .toList();
    }

    /**
     * The manufacturer list. <b>Empty until the catalogue is seeded</b> — see
     * {@code docs/deferred.md}. An empty array is the honest answer: there is no catalogue, and
     * a client seeing none knows to fall back to the free-text make field, which is exactly what
     * the listing API accepts today.
     */
    public List<ReferenceDtos.Make> makes() {
        return makes.findAllActive().stream()
                .map(make -> new ReferenceDtos.Make(
                        make.getId(), make.getSlug(), make.getName(), make.isPopular()))
                .toList();
    }

    public List<ReferenceDtos.Model> models(UUID makeId) {
        // 404 on an unknown make rather than an empty list: those mean different things, and a
        // client that cannot tell them apart will show "no models" for a typo in the id.
        makes.findActiveById(makeId)
                .orElseThrow(() -> ApiException.notFound("Make " + makeId));

        return models.findActiveByMake(makeId).stream()
                .map(model -> new ReferenceDtos.Model(
                        model.getId(), model.getSlug(), model.getName(),
                        model.getYearFrom(), model.getYearTo()))
                .toList();
    }

    /** Only enabled countries: a known-but-closed market is not something to offer in a form. */
    public List<ReferenceDtos.Country> countries() {
        return countries.findAllEnabled().stream()
                .map(country -> new ReferenceDtos.Country(
                        country.getCode(),
                        country.getName(),
                        country.getDefaultLocale(),
                        country.getPhonePrefix(),
                        country.getDistanceUnit(),
                        country.getDefaultCurrency().getCode(),
                        country.getDefaultCurrency().getMinorUnit()))
                .toList();
    }

    private static Map<String, Class<? extends Enum<?>>> publishedEnums() {
        Map<String, Class<? extends Enum<?>>> published = new LinkedHashMap<>();

        // Vehicle description — the search filters and the listing form.
        published.put("FuelType", FuelType.class);
        published.put("Gearbox", Gearbox.class);
        published.put("BodyType", BodyType.class);
        published.put("Drivetrain", Drivetrain.class);
        published.put("VehicleCondition", VehicleCondition.class);
        published.put("ExteriorColor", ExteriorColor.class);
        published.put("InteriorMaterial", InteriorMaterial.class);
        published.put("EmissionClass", EmissionClass.class);
        published.put("FeatureCategory", FeatureCategory.class);

        // Listing and image state, which a seller's dashboard renders.
        published.put("ListingStatus", ListingStatus.class);
        published.put("ModerationStatus", ModerationStatus.class);
        published.put("ImageStatus", ImageStatus.class);
        published.put("ImageVariantType", ImageVariantType.class);
        // Moderation. The reasons are also what a seller is told, so a client needs the full set.
        published.put("ModerationReason", com.autox.listing.ModerationReason.class);
        published.put("ModerationActionType", com.autox.listing.ModerationActionType.class);
        published.put("ModerationView", com.autox.listing.ModerationView.class);

        // Account.
        published.put("UserRole", UserRole.class);
        published.put("UserStatus", UserStatus.class);

        // Presentation and protocol.
        published.put("DistanceUnit", DistanceUnit.class);
        published.put("SearchSort", SearchSort.class);
        published.put("PriceLevel", com.autox.search.PriceLevel.class);
        published.put("ValuationScope", com.autox.search.ValuationScope.class);

        // Messages.
        published.put("ConversationRole", com.autox.messaging.ConversationRole.class);
        published.put("MessageReportReason", com.autox.messaging.MessageReportReason.class);
        // Included on purpose: clients are told to branch on `code`, so the set of codes they
        // may receive is part of the contract, not an implementation detail.
        published.put("ErrorCode", ErrorCode.class);

        // unmodifiableMap, not Map.copyOf: the latter does not preserve iteration order, and the
        // grouping above is the order a client would reasonably render.
        return java.util.Collections.unmodifiableMap(published);
    }

    /** Exposed for the test that asserts the published set matches what the API actually returns. */
    static Map<String, Class<? extends Enum<?>>> publishedEnumTypes() {
        return PUBLISHED_ENUMS;
    }
}
