package com.autox.demo;

import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.catalog.VehicleModel;
import com.autox.catalog.VehicleModelRepository;
import com.autox.listing.ListingImageRepository;
import com.autox.listing.ListingService;
import com.autox.listing.dto.CreateListingRequest;
import com.autox.listing.dto.ImageUploadRequest;
import com.autox.listing.dto.ImageUploadTicket;
import com.autox.listing.image.ImageResizer;
import com.autox.listing.image.ListingImageService;
import com.autox.reference.Country;
import com.autox.reference.CountryRepository;
import com.autox.storage.ObjectStorage;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.ProProfile;
import com.autox.user.ProProfileRepository;
import com.autox.user.UserRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates and removes the demo data. Exists only under the {@code demo} profile.
 *
 * <h2>How it is kept apart from real data</h2>
 *
 * <ul>
 *   <li><b>It is not in the application unless asked for.</b> Every class in this package
 *       is {@code @Profile("demo")}; without that profile the beans do not exist, there
 *       is no endpoint, and nothing can trigger a seed.</li>
 *   <li><b>It goes through the front door.</b> Listings are created, given a photo and
 *       published through the same services the API uses, so demo data obeys every rule
 *       real data does. The alternative — SQL inserts — produces rows that look fine and
 *       quietly violate an invariant the screen then trips over.</li>
 *   <li><b>It writes down what it made.</b> Every row created is recorded in
 *       {@link DemoLedger}, and removal deletes exactly those rows. Not "accounts whose
 *       address looks like a demo one" and not "makes named Peugeot": if a real catalogue
 *       is loaded later, removing the demo cannot take a single real row with it.</li>
 * </ul>
 */
@Service
@Profile("demo")
class DemoData {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final DemoLedger ledger;
    private final AppUserRepository users;
    private final ProProfileRepository proProfiles;
    private final CountryRepository countries;
    private final VehicleMakeRepository makes;
    private final VehicleModelRepository models;
    private final ListingService listings;
    private final ListingImageService imageService;
    private final ListingImageRepository images;
    private final ImageResizer resizer;
    private final ObjectStorage storage;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final String photoDirectory;
    private final TransactionTemplate transactions;

    /**
     * How many of the demo listings to create. All of them, unless told otherwise: every
     * listing is a real image upload with three resizes, so a test that only needs "some
     * listings" asks for a dozen instead of waiting for sixty.
     */
    private final int limit;

    DemoData(DemoLedger ledger, AppUserRepository users, ProProfileRepository proProfiles,
             CountryRepository countries, VehicleMakeRepository makes, VehicleModelRepository models,
             ListingService listings, ListingImageService imageService, ListingImageRepository images,
             ImageResizer resizer, ObjectStorage storage, PasswordEncoder passwordEncoder,
             JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
             @Value("${autox.demo.photo-dir:}") String photoDirectory,
             @Value("${autox.demo.listings:60}") int limit) {
        this.ledger = ledger;
        this.users = users;
        this.proProfiles = proProfiles;
        this.countries = countries;
        this.makes = makes;
        this.models = models;
        this.listings = listings;
        this.imageService = imageService;
        this.images = images;
        this.resizer = resizer;
        this.storage = storage;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.photoDirectory = photoDirectory;
        this.limit = limit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Seeds once. A second call is a no-op, so leaving the profile switched on across
     * restarts does not pile up fourteen more listings each time.
     *
     * @return how many listings were created — zero when the seed was already present
     */
    int seed() {
        ledger.create();
        if (ledger.hasEntries()) {
            log.info("Demo data is already present; nothing seeded. "
                    + "Start once with AUTOX_DEMO_ACTION=remove to clear it.");
            return 0;
        }

        Country france = countries.findEnabledByCode("FR")
                .orElseThrow(() -> new IllegalStateException("The demo seed needs France enabled."));

        // Storage before the first row. This runs before StorageReadinessCheck, which waits
        // for the application to be ready — so on a fresh local stack the bucket did not
        // exist yet, and a storage fault anywhere failed the seed halfway, leaving a ledger
        // that makes every later start skip it. Here it either works or nothing is written.
        storage.ensureReady();

        Map<String, UUID> sellerIds = seedSellers(france);
        Map<String, UUID[]> catalogue = seedCatalogue();

        int created = 0;
        List<DemoCatalogue.Vehicle> vehicles = DemoCatalogue.vehicles();
        for (DemoCatalogue.Vehicle vehicle : vehicles.subList(0, Math.min(limit, vehicles.size()))) {
            seedListing(vehicle, sellerIds.get(vehicle.sellerKey()),
                    catalogue.get(vehicle.makeSlug() + "/" + vehicle.modelSlug()));
            created++;
        }

        log.warn("Seeded {} demo listings, {} demo accounts and {} demo makes. This is invented data "
                        + "— remove it with AUTOX_DEMO_ACTION=remove.",
                created, sellerIds.size(), DemoCatalogue.MAKES.size());
        return created;
    }

    /**
     * Removes exactly what {@link #seed()} created, and the ledger with it.
     *
     * <p>Stored objects first, while the rows that name them still exist. Then listings —
     * their images, variants, features and price history go with them by cascade — then
     * the accounts, then the catalogue rows, children before parents.
     */
    int remove() {
        if (!ledger.exists()) {
            log.info("No demo data to remove.");
            return 0;
        }

        List<UUID> listingIds = ledger.ids(DemoLedger.LISTING);
        List<String> keys = new ArrayList<>();
        for (UUID listingId : listingIds) {
            keys.addAll(jdbc.queryForList(
                    "SELECT storage_key FROM listing_image WHERE listing_id = ?", String.class, listingId));
            keys.addAll(jdbc.queryForList("""
                    SELECT v.storage_key FROM listing_image_variant v
                      JOIN listing_image i ON i.id = v.image_id
                     WHERE i.listing_id = ?""", String.class, listingId));
        }
        if (!keys.isEmpty()) {
            storage.deleteAll(keys);
        }

        listingIds.forEach(id -> jdbc.update("DELETE FROM listing WHERE id = ?", id));
        ledger.ids(DemoLedger.USER).forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        ledger.ids(DemoLedger.MODEL).forEach(id -> jdbc.update("DELETE FROM vehicle_model WHERE id = ?", id));
        ledger.ids(DemoLedger.MAKE).forEach(id -> jdbc.update("DELETE FROM vehicle_make WHERE id = ?", id));
        ledger.drop();

        log.warn("Removed the demo data: {} listings and {} stored objects.", listingIds.size(), keys.size());
        return listingIds.size();
    }

    private Map<String, UUID> seedSellers(Country france) {
        Map<String, UUID> ids = new HashMap<>();
        for (DemoCatalogue.Seller seller : DemoCatalogue.SELLERS) {
            // A random password nobody is told. These accounts exist to own listings, not
            // to be signed in to — and a known demo password would be a credential in the
            // repository, which is the one thing the project's rules forbid outright.
            String passwordHash = passwordEncoder.encode(UUID.randomUUID().toString());

            // One transaction for the account and its company row, as registration does it.
            // Outside one the saved account is detached by the time the profile is saved,
            // and the profile's shared primary key makes Hibernate try to insert it again.
            UUID userId = transactions.execute(status -> {
                AppUser user = AppUser.register(seller.email(), passwordHash,
                        seller.role(), seller.displayName(), france, "fr-FR");
                // No cap (0) on these accounts, whatever the deployment caps real ones at:
                // the dealers own more listings each than a public demo lets a stranger
                // publish, and the seed failed halfway the first time it met such a cap.
                user.setActiveListingLimit((short) 0);
                users.saveAndFlush(user);
                if (seller.role() == UserRole.PROFESSIONNEL) {
                    proProfiles.save(ProProfile.forUser(user, seller.companyName(), seller.registrationNumber()));
                }
                return user.getId();
            });
            ledger.record(DemoLedger.USER, userId);
            ids.put(seller.key(), userId);
        }
        return ids;
    }

    /** @return {@code "make/model"} slug pair to {@code [makeId, modelId]} */
    private Map<String, UUID[]> seedCatalogue() {
        Map<String, UUID[]> ids = new HashMap<>();
        for (DemoCatalogue.Make definition : DemoCatalogue.MAKES) {
            // Reuse a make that is already there, and do not write it in the ledger. Slugs
            // are unique, so creating a second "peugeot" would fail — and, more to the
            // point, a row the seed did not create is a row removal must never delete.
            VehicleMake make = existingId("SELECT id FROM vehicle_make WHERE slug = ?", definition.slug())
                    .flatMap(makes::findById)
                    .orElseGet(() -> {
                        VehicleMake created = VehicleMake.of(definition.slug(), definition.name());
                        created.setPopular(definition.popular());
                        makes.saveAndFlush(created);
                        ledger.record(DemoLedger.MAKE, created.getId());
                        return created;
                    });

            for (DemoCatalogue.Model modelDefinition : definition.models()) {
                VehicleModel model = existingId(
                        "SELECT id FROM vehicle_model WHERE make_id = ? AND slug = ?",
                        make.getId(), modelDefinition.slug())
                        .flatMap(models::findById)
                        .orElseGet(() -> {
                            VehicleModel created =
                                    VehicleModel.of(make, modelDefinition.slug(), modelDefinition.name());
                            models.saveAndFlush(created);
                            ledger.record(DemoLedger.MODEL, created.getId());
                            return created;
                        });
                ids.put(definition.slug() + "/" + modelDefinition.slug(), new UUID[] {make.getId(), model.getId()});
            }
        }
        return ids;
    }

    private java.util.Optional<UUID> existingId(String sql, Object... arguments) {
        return jdbc.queryForList(sql, UUID.class, arguments).stream().findFirst();
    }

    private void seedListing(DemoCatalogue.Vehicle vehicle, UUID sellerId, UUID[] catalogueIds) {
        CreateListingRequest request = new CreateListingRequest(
                vehicle.title(),
                DemoDetails.description(vehicle),
                vehicle.priceEuros() * 100,
                "EUR",
                false,
                null,
                null,
                vehicle.postalCode(),
                vehicle.city(),
                "FR",
                null,
                catalogueIds[0],
                catalogueIds[1],
                null,
                null,
                null,
                vehicle.version(),
                vehicle.year(),
                vehicle.month(),
                vehicle.mileageKm(),
                vehicle.fuel(),
                vehicle.gearbox(),
                vehicle.body(),
                null,
                vehicle.powerHp(),
                DemoDetails.powerKw(vehicle),
                null,
                DemoDetails.doors(vehicle),
                DemoDetails.seats(vehicle),
                vehicle.condition(),
                vehicle.colour(),
                null,
                null,
                null,
                DemoDetails.owners(vehicle),
                vehicle.condition() == com.autox.listing.VehicleCondition.NEW ? null : Boolean.TRUE,
                null,
                null,
                DemoDetails.co2(vehicle),
                DemoDetails.emissionClass(vehicle),
                // No VIN. A made-up one would be a real-looking identifier for a car that
                // does not exist, and the public API does not show it anyway.
                null,
                DemoDetails.features(vehicle));

        UUID listingId = listings.create(sellerId, request).id();
        ledger.record(DemoLedger.LISTING, listingId);

        // The real three-step upload, with the seeder standing in for the browser: ask for
        // a slot, put the bytes where the slot says, confirm. That is what produces the
        // three variants the results page actually displays.
        //
        // Three per listing — a side view each way and a closer one — because a gallery
        // with one picture has no thumbnails, no arrows and nothing to enlarge to.
        for (int view = 0; view < PHOTOS_PER_LISTING; view++) {
            byte[] photo = photoFor(vehicle, view);
            ImageUploadTicket ticket = imageService.requestUpload(sellerId, false, listingId,
                    new ImageUploadRequest(vehicle.makeSlug() + "-" + vehicle.modelSlug() + "-" + (view + 1) + ".jpg",
                            "image/jpeg", (long) photo.length));
            String key = images.findByIdAndListing(ticket.imageId(), listingId).orElseThrow().getStorageKey();
            storage.write(key, photo, "image/jpeg");
            imageService.confirmUpload(sellerId, false, listingId, ticket.imageId());
        }

        listings.publish(sellerId, false, listingId);

        // The one direct write, and it is why this is a seeder and not a script against the
        // API: every listing published in the same second would make "plus récentes" an
        // arbitrary order. Spreading the dates is the only way to see sorting work.
        jdbc.update("UPDATE listing SET published_at = now() - make_interval(days => ?) WHERE id = ?",
                vehicle.publishedDaysAgo(), listingId);
    }

    static final int PHOTOS_PER_LISTING = 3;

    /**
     * A JPEG from {@code autox.demo.photo-dir}, or a drawing.
     *
     * <p>The files are {@code make-model.jpg} for the first photo and
     * {@code make-model-2.jpg}, {@code make-model-3.jpg} for the others.
     */
    private byte[] photoFor(DemoCatalogue.Vehicle vehicle, int view) {
        if (!photoDirectory.isBlank()) {
            String suffix = view == 0 ? "" : "-" + (view + 1);
            Path candidate = Path.of(photoDirectory,
                    vehicle.makeSlug() + "-" + vehicle.modelSlug() + suffix + ".jpg");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readAllBytes(candidate);
                } catch (IOException e) {
                    log.warn("Could not read {}; using a drawing instead.", candidate, e);
                }
            }
        }
        return resizer.encodeJpeg(
                DemoPhotoPainter.paint(vehicle.body(), vehicle.colour(), vehicle.title(), view), 0.9f);
    }
}
