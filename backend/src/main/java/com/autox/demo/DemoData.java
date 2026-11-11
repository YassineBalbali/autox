package com.autox.demo;

import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.catalog.VehicleModel;
import com.autox.catalog.VehicleModelRepository;
import com.autox.listing.ListingImageRepository;
import com.autox.listing.ListingService;
import com.autox.listing.dto.CreateListingRequest;
import com.autox.listing.OfferType;
import com.autox.listing.dto.DamageDeclaration;
import com.autox.listing.dto.LeasingDeclaration;
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
    private final com.autox.listing.image.ImageProperties imageProperties;
    private final ObjectStorage storage;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final String photoDirectory;
    private final TransactionTemplate transactions;

    /**
     * How many of the demo listings to create. All of them, unless told otherwise: every
     * listing is a real image upload with three resizes, so a test that only needs "some
     * listings" asks for a dozen instead of waiting for all of them.
     */
    private final int limit;

    /** How many of {@link DemoCatalogue#DAMAGED} to create, for the same reason. */
    private final int damagedLimit;

    /** And of {@link DemoCatalogue#LEASED}. */
    private final int leasedLimit;

    /** And of {@link DemoCatalogue#NEW_CARS}. */
    private final int newLimit;

    DemoData(DemoLedger ledger, AppUserRepository users, ProProfileRepository proProfiles,
             CountryRepository countries, VehicleMakeRepository makes, VehicleModelRepository models,
             ListingService listings, ListingImageService imageService, ListingImageRepository images,
             ImageResizer resizer, com.autox.listing.image.ImageProperties imageProperties,
             ObjectStorage storage, PasswordEncoder passwordEncoder,
             JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
             @Value("${autox.demo.photo-dir:}") String photoDirectory,
             @Value("${autox.demo.listings:" + DemoCatalogue.SIZE + "}") int limit,
             @Value("${autox.demo.damaged-listings:8}") int damagedLimit,
             @Value("${autox.demo.leasing-listings:12}") int leasedLimit,
             @Value("${autox.demo.new-listings:15}") int newLimit) {
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
        this.imageProperties = imageProperties;
        this.storage = storage;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.photoDirectory = photoDirectory;
        this.limit = limit;
        this.damagedLimit = Math.min(damagedLimit, DemoCatalogue.DAMAGED.size());
        this.leasedLimit = Math.min(leasedLimit, DemoCatalogue.LEASED.size());
        this.newLimit = Math.min(newLimit, DemoCatalogue.NEW_CARS.size());
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Seeds once. A second call creates no listing, account or photo, so leaving the profile
     * switched on across restarts does not pile up more listings each time. It does insert
     * any catalogue make or model added to {@link DemoCatalogue} since ({@link #topUpCatalogue}),
     * and only those: one boot with the profile brings a seeded database's catalogue up to date.
     * It also approves the seed's listings still awaiting moderation ({@link #approvePending}).
     *
     * @return how many listings were created — zero when the seed was already present
     */
    int seed() {
        ledger.create();
        List<DemoCatalogue.Vehicle> vehicles = DemoCatalogue.vehicles();
        int target = Math.min(limit, vehicles.size());
        if (ledger.hasEntries()) {
            // The catalogue first, and whatever the listings' state: makes and models added to
            // DemoCatalogue since the seed ran are inserted, and nothing else is written.
            topUpCatalogue();
            approvePending();
            correctListings();
            completeDealerPages();
            // Only what an earlier seed recorded counts. Removal drops the whole ledger, so a
            // make or model it kept for someone else's listing is not in here, and a seed
            // that was stopped partway is told apart from one that finished.
            int recorded = ledger.ids(DemoLedger.LISTING).size();
            if (recorded >= target) {
                addMissingDamaged();
                addMissingLeased();
                addMissingNew();
                log.info("Demo data is already present ({} listings); nothing seeded.",
                        ledger.ids(DemoLedger.LISTING).size());
            } else {
                log.warn("An earlier demo seed stopped partway: {} of {} listings. Nothing seeded. "
                                + "Start once with AUTOX_DEMO_ACTION=remove, which deletes only what the "
                                + "seed recorded, then once without it to seed from the start.",
                        recorded, target);
            }
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
        Map<String, UUID[]> catalogue = seedCatalogue().ids();

        int created = 0;
        for (DemoCatalogue.Vehicle vehicle : vehicles.subList(0, target)) {
            seedListing(vehicle, sellerIds.get(vehicle.sellerKey()),
                    catalogue.get(vehicle.makeSlug() + "/" + vehicle.modelSlug()),
                    DemoDetails.description(vehicle), null, null);
            created++;
        }
        created += addMissingDamaged();
        created += addMissingLeased();
        created += addMissingNew();
        completeDealerPages();

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
        // A conversation outlives its listing (it is the other party's too), so a visitor's
        // conversation with a demo seller is still here, and would hold the account back. A demo
        // seller never answered: there is nothing in it for the visitor to keep.
        ledger.ids(DemoLedger.USER).forEach(id ->
                jdbc.update("DELETE FROM conversation WHERE buyer_id = ? OR seller_id = ?", id, id));
        ledger.ids(DemoLedger.USER).forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        // A make or model someone else's listing uses stays. Visitors' listings are left
        // alone, and a visitor who sold a "Peugeot 308" picked the demo's 308: deleting it
        // failed on the foreign key halfway through, after the listings and accounts had
        // gone. Kept, it is an ordinary catalogue row from then on, and a later seed reuses it.
        ledger.ids(DemoLedger.MODEL).forEach(id -> jdbc.update("""
                DELETE FROM vehicle_model m WHERE id = ?
                   AND NOT EXISTS (SELECT 1 FROM listing WHERE model_id = m.id)
                   AND NOT EXISTS (SELECT 1 FROM vehicle_trim WHERE model_id = m.id)""", id));
        ledger.ids(DemoLedger.MAKE).forEach(id -> jdbc.update("""
                DELETE FROM vehicle_make k WHERE id = ?
                   AND NOT EXISTS (SELECT 1 FROM listing WHERE make_id = k.id)
                   AND NOT EXISTS (SELECT 1 FROM vehicle_model WHERE make_id = k.id)""", id));
        Integer kept = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM vehicle_model WHERE id IN (SELECT entity_id FROM demo_seed_ledger
                         WHERE entity_type = ?))
                     + (SELECT count(*) FROM vehicle_make WHERE id IN (SELECT entity_id FROM demo_seed_ledger
                         WHERE entity_type = ?))""", Integer.class, DemoLedger.MODEL, DemoLedger.MAKE);
        ledger.drop();

        log.warn("Removed the demo data: {} listings and {} stored objects. {} demo makes and models "
                        + "were kept because other listings use them.", listingIds.size(), keys.size(), kept);
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

    /**
     * Inserts the makes and models of {@link DemoCatalogue#MAKES} that the database does not
     * have, on a database already seeded. Listings, accounts and photos are not touched.
     *
     * <p>Idempotent: an entry is looked up by its slug — a make by its own, a model by its
     * make's and its own — and one that exists is left exactly as it is, whatever its name,
     * whether the seed created it or not. A second run finds everything and inserts nothing.
     * What it does insert is recorded in the ledger, so a removal takes it away with the rest.
     *
     * @return how many makes and models were inserted
     */
    int topUpCatalogue() {
        CatalogueRows rows = seedCatalogue();
        if (rows.makesAdded() + rows.modelsAdded() == 0) {
            log.info("Demo catalogue: complete, nothing added ({} makes).", DemoCatalogue.MAKES.size());
        } else {
            log.warn("Demo catalogue: inserted what was missing (makes: {}, models: {}); listings untouched.",
                    rows.makesAdded(), rows.modelsAdded());
        }
        return rows.makesAdded() + rows.modelsAdded();
    }

    /**
     * Creates the leasing offers of {@link DemoCatalogue#LEASED} this database lacks, the same way
     * and on the same terms as {@link #addMissingDamaged}.
     *
     * @return how many listings were created
     */
    int addMissingLeased() {
        java.util.Set<String> present = titlesInTheLedger();
        List<DemoCatalogue.LeasedVehicle> missing = DemoCatalogue.LEASED.subList(0, leasedLimit).stream()
                .filter(leased -> !present.contains(leased.vehicle().title()))
                .toList();
        if (missing.isEmpty()) {
            log.info("Demo leasing offers: complete, nothing added ({} offers).", leasedLimit);
            return 0;
        }

        storage.ensureReady();
        Map<String, UUID[]> catalogue = seedCatalogue().ids();
        for (DemoCatalogue.LeasedVehicle leased : missing) {
            DemoCatalogue.Vehicle vehicle = leased.vehicle();
            seedListing(vehicle, demoSeller(vehicle), catalogue.get(vehicle.makeSlug() + "/" + vehicle.modelSlug()),
                    DemoDetails.leasedDescription(leased), null,
                    new LeasingDeclaration(OfferType.LEASING, leased.type(), leased.monthlyEuros() * 100,
                            leased.firstPaymentEuros() * 100, leased.durationMonths(), leased.mileagePerYear(),
                            leased.optionEuros() == null ? null : leased.optionEuros() * 100));
        }
        log.warn("Demo leasing offers: added {} of {}; other listings untouched.", missing.size(), leasedLimit);
        return missing.size();
    }

    /**
     * The new cars of {@link DemoCatalogue#NEW_CARS} a seeded database lacks, by title, as for the
     * leasing offers: the same procedure, through the API, recorded in the ledger. A second run
     * adds nothing.
     *
     * @return how many were added
     */
    int addMissingNew() {
        java.util.Set<String> present = titlesInTheLedger();
        List<DemoCatalogue.NewVehicle> missing = DemoCatalogue.NEW_CARS.subList(0, newLimit).stream()
                .filter(car -> !present.contains(car.vehicle().title()))
                .toList();
        if (missing.isEmpty()) {
            log.info("Demo new cars: complete, nothing added ({} cars).", newLimit);
            return 0;
        }

        storage.ensureReady();
        Map<String, UUID[]> catalogue = seedCatalogue().ids();
        for (DemoCatalogue.NewVehicle car : missing) {
            DemoCatalogue.Vehicle vehicle = car.vehicle();
            seedListing(vehicle, demoSeller(vehicle), catalogue.get(vehicle.makeSlug() + "/" + vehicle.modelSlug()),
                    DemoDetails.newDescription(car), null, null);
        }
        log.warn("Demo new cars: added {} of {}; other listings untouched.", missing.size(), newLimit);
        return missing.size();
    }

    private java.util.Set<String> titlesInTheLedger() {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT l.title FROM listing l
                  JOIN demo_seed_ledger d ON d.entity_type = ? AND d.entity_id = l.id""",
                String.class, DemoLedger.LISTING));
    }

    private UUID demoSeller(DemoCatalogue.Vehicle vehicle) {
        return jdbc.queryForObject("""
                SELECT u.id FROM app_user u
                  JOIN demo_seed_ledger d ON d.entity_type = ? AND d.entity_id = u.id
                 WHERE u.email = ?""", UUID.class, DemoLedger.USER, vehicle.sellerKey() + DemoCatalogue.EMAIL_DOMAIN);
    }

    /**
     * Gives the seed's dealers what their public page says about them: the description and opening
     * hours of {@link DemoCatalogue#DEALER_PAGES}.
     *
     * <p>Through the ledger, so no visitor's company can be reached, and by address, which the seed
     * wrote. Only what differs is written: a second run changes nothing. Nothing else about the
     * profile is touched, the logo least of all: the demo dealers have none.
     *
     * @return how many dealers' pages were changed
     */
    int completeDealerPages() {
        int changed = 0;
        for (Map.Entry<String, DemoCatalogue.DealerPageText> page : DemoCatalogue.DEALER_PAGES.entrySet()) {
            changed += jdbc.update("""
                    UPDATE pro_profile p SET description = ?, opening_hours = ?
                      FROM app_user u
                     WHERE u.id = p.user_id AND u.email = ?
                       AND u.id IN (SELECT entity_id FROM demo_seed_ledger WHERE entity_type = ?)
                       AND (p.description, p.opening_hours) IS DISTINCT FROM (?, ?)""",
                    page.getValue().description(), page.getValue().openingHours(),
                    page.getKey() + DemoCatalogue.EMAIL_DOMAIN, DemoLedger.USER,
                    page.getValue().description(), page.getValue().openingHours());
        }
        if (changed == 0) {
            log.info("Demo dealer pages: complete, nothing changed ({} dealers).", DemoCatalogue.DEALER_PAGES.size());
        } else {
            log.warn("Demo dealer pages: gave {} of {} dealers a description and opening hours; nothing else changed.",
                    changed, DemoCatalogue.DEALER_PAGES.size());
        }
        return changed;
    }

    /**
     * Approves the seed's listings still in the moderation queue, on a database already seeded.
     * An earlier seed left them there: the queue is for what visitors publish, and invented
     * listings buried the real ones in it.
     *
     * <p>Through the ledger, so no visitor's listing can be reached, and only what the queue
     * holds: a listing a moderator refused or removed keeps that verdict, and a sold one or a
     * draft is not awaiting anyone. It sets the verdict and nothing else, not even
     * {@code updated_at}. A second run finds nothing to approve.
     *
     * <p>Approved by the system, so with no {@code moderation_action} row: that trail names the
     * administrator who decided, and here none did.
     *
     * @return how many listings were approved
     */
    int approvePending() {
        int approved = jdbc.update("""
                UPDATE listing SET moderation_status = 'APPROVED'
                 WHERE status = 'ACTIVE' AND moderation_status = 'NOT_REVIEWED' AND deleted_at IS NULL
                   AND id IN (SELECT entity_id FROM demo_seed_ledger WHERE entity_type = ?)""",
                DemoLedger.LISTING);
        if (approved == 0) {
            log.info("Demo listings: none awaiting moderation, nothing approved.");
        } else {
            log.warn("Demo listings: approved {} awaiting moderation, by the system; nothing else changed.", approved);
        }
        return approved;
    }

    /** What {@link #correctListings} did. */
    record Corrections(int descriptions, int fields, int redrawn, int photos, int unmatched) {
        boolean none() {
            return descriptions + fields + redrawn == 0;
        }
    }

    /**
     * Brings the seed's listings on a database already seeded into line with {@link DemoCatalogue}
     * and {@link DemoDetails} as they are now: the description, the body, doors, seats, fuel and
     * power, and the photos, redrawn in the listing's body type.
     *
     * <p>Through the ledger, so no visitor's listing can be reached. A listing is matched to its
     * car by its title, which the seed wrote and nothing here changes; the generated set has cars
     * with the same title, and they share everything corrected here except the town, which is read
     * from the listing. One whose title is in no list is left alone and counted.
     *
     * <p>Only what differs is written, and nothing else: not the status, not the verdict, not
     * {@code updated_at}, so the listing neither re-enters the moderation queue nor looks edited.
     * The photos keep their rows and their keys; the bytes behind them are replaced, and the sizes
     * recorded with them. A listing whose photos have been redrawn is marked in the ledger
     * ({@link DemoLedger#DRAWING}) and not redrawn again. A second run finds nothing to do.
     */
    Corrections correctListings() {
        Map<String, Expected> expected = expectations();
        int descriptions = 0;
        int fields = 0;
        int redrawn = 0;
        int photos = 0;
        int unmatched = 0;
        java.util.Set<UUID> drawn = new java.util.HashSet<>(ledger.ids(DemoLedger.DRAWING));

        for (UUID listingId : ledger.ids(DemoLedger.LISTING)) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT title, city, exterior_color FROM listing WHERE id = ?", listingId);
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, Object> row = rows.getFirst();
            Expected car = expected.get((String) row.get("title"));
            if (car == null) {
                unmatched++;
                continue;
            }
            DemoCatalogue.Vehicle vehicle = car.vehicle();
            String description = car.description().apply((String) row.get("city"));

            descriptions += jdbc.update("""
                    UPDATE listing SET description = ? WHERE id = ? AND description IS DISTINCT FROM ?""",
                    description, listingId, description);
            fields += jdbc.update("""
                    UPDATE listing SET body_type = ?, doors = ?, seats = ?, fuel_type = ?, power_hp = ?, power_kw = ?
                     WHERE id = ?
                       AND (body_type, doors, seats, fuel_type, power_hp, power_kw)
                           IS DISTINCT FROM (?, ?::smallint, ?::smallint, ?, ?::smallint, ?::smallint)""",
                    vehicle.body().name(), DemoDetails.doors(vehicle), DemoDetails.seats(vehicle),
                    vehicle.fuel().name(), vehicle.powerHp(), DemoDetails.powerKw(vehicle), listingId,
                    vehicle.body().name(), DemoDetails.doors(vehicle), DemoDetails.seats(vehicle),
                    vehicle.fuel().name(), vehicle.powerHp(), DemoDetails.powerKw(vehicle));

            if (!drawn.contains(listingId)) {
                com.autox.listing.ExteriorColor colour = row.get("exterior_color") == null ? vehicle.colour()
                        : com.autox.listing.ExteriorColor.valueOf((String) row.get("exterior_color"));
                photos += redraw(listingId, vehicle, colour, (String) row.get("title"));
                ledger.record(DemoLedger.DRAWING, listingId);
                redrawn++;
            }
        }

        Corrections done = new Corrections(descriptions, fields, redrawn, photos, unmatched);
        if (done.none()) {
            log.info("Demo listings: all match the catalogue, nothing corrected ({} not matched).", unmatched);
        } else {
            log.warn("Demo listings corrected in place: {} descriptions, {} with body, doors, seats, fuel or "
                            + "power, {} redrawn ({} photos); {} not matched. Status, verdict and dates untouched.",
                    descriptions, fields, redrawn, photos, unmatched);
        }
        return done;
    }

    /** A demo car and its description, given the town it is in. */
    private record Expected(DemoCatalogue.Vehicle vehicle, java.util.function.Function<String, String> description) {
    }

    /** By title: every listing the seed can have written, with what it should say. */
    private static Map<String, Expected> expectations() {
        Map<String, Expected> byTitle = new HashMap<>();
        for (DemoCatalogue.Vehicle vehicle : DemoCatalogue.vehicles()) {
            byTitle.putIfAbsent(vehicle.title(),
                    new Expected(vehicle, city -> DemoDetails.description(vehicle.in(city))));
        }
        for (DemoCatalogue.DamagedVehicle damaged : DemoCatalogue.DAMAGED) {
            byTitle.put(damaged.vehicle().title(),
                    new Expected(damaged.vehicle(), city -> DemoDetails.damagedDescription(damaged)));
        }
        for (DemoCatalogue.LeasedVehicle leased : DemoCatalogue.LEASED) {
            byTitle.put(leased.vehicle().title(),
                    new Expected(leased.vehicle(), city -> DemoDetails.leasedDescription(leased)));
        }
        return byTitle;
    }

    /**
     * Draws a listing's photos again and puts the new bytes where the old ones were: the original
     * and each variant, under the same keys, with their sizes. The photo at position {@code n} is
     * view {@code n} of three, as the seed drew it.
     *
     * @return how many photos were redrawn
     */
    private int redraw(UUID listingId, DemoCatalogue.Vehicle vehicle, com.autox.listing.ExteriorColor colour,
                       String title) {
        List<Map<String, Object>> photos = jdbc.queryForList("""
                SELECT id, position, storage_key FROM listing_image
                 WHERE listing_id = ? AND status = 'READY' ORDER BY position""", listingId);
        DemoCatalogue.Vehicle painted = vehicle.coloured(colour).titled(title);
        for (Map<String, Object> photo : photos) {
            UUID imageId = (UUID) photo.get("id");
            int view = ((Number) photo.get("position")).intValue() % PHOTOS_PER_LISTING;
            byte[] original = photoFor(painted, view);
            java.awt.image.BufferedImage decoded = resizer.decode(original);

            storage.write((String) photo.get("storage_key"), original, "image/jpeg");
            jdbc.update("""
                    UPDATE listing_image SET byte_size = ?, width = ?, height = ?, checksum_sha256 = ?
                     WHERE id = ?""",
                    (long) original.length, decoded.getWidth(), decoded.getHeight(),
                    com.autox.common.Sha256.hex(original), imageId);

            for (Map<String, Object> variant : jdbc.queryForList(
                    "SELECT id, variant, storage_key FROM listing_image_variant WHERE image_id = ?", imageId)) {
                com.autox.listing.ImageVariantType type =
                        com.autox.listing.ImageVariantType.valueOf((String) variant.get("variant"));
                Integer edge = imageProperties.variantMaxEdge().get(type);
                if (edge == null) {
                    continue;
                }
                ImageResizer.Rendition rendition = resizer.render(decoded, edge, imageProperties.jpegQuality());
                storage.write((String) variant.get("storage_key"), rendition.content(), "image/jpeg");
                jdbc.update("UPDATE listing_image_variant SET byte_size = ?, width = ?, height = ? WHERE id = ?",
                        (long) rendition.content().length, rendition.width(), rendition.height(), variant.get("id"));
            }
        }
        return photos.size();
    }

    /**
     * Creates the damaged vehicles of {@link DemoCatalogue#DAMAGED} this database lacks, through
     * the same pipeline as any other demo listing and recorded in the ledger, so a removal takes
     * them with the rest.
     *
     * <p>Idempotent: one is looked up by its title among the listings in the ledger, and one that
     * is there is left as it is, whatever has happened to it since. No other listing, account or
     * photo is touched, and no visitor's listing can match, however it is titled.
     *
     * @return how many listings were created
     */
    int addMissingDamaged() {
        java.util.Set<String> present = titlesInTheLedger();
        List<DemoCatalogue.DamagedVehicle> missing = DemoCatalogue.DAMAGED.subList(0, damagedLimit).stream()
                .filter(damaged -> !present.contains(damaged.vehicle().title()))
                .toList();
        if (missing.isEmpty()) {
            log.info("Demo damaged listings: complete, nothing added ({} listings).", damagedLimit);
            return 0;
        }

        // As for a first seed: either storage works or nothing is written.
        storage.ensureReady();
        Map<String, UUID[]> catalogue = seedCatalogue().ids();
        for (DemoCatalogue.DamagedVehicle damaged : missing) {
            DemoCatalogue.Vehicle vehicle = damaged.vehicle();
            seedListing(vehicle, demoSeller(vehicle), catalogue.get(vehicle.makeSlug() + "/" + vehicle.modelSlug()),
                    DemoDetails.damagedDescription(damaged),
                    new DamageDeclaration(true, damaged.types(), damaged.running(), damaged.description()), null);
        }
        log.warn("Demo damaged listings: added {} of {}; other listings untouched.", missing.size(), damagedLimit);
        return missing.size();
    }

    /**
     * @param ids          {@code "make/model"} slug pair to {@code [makeId, modelId]}
     * @param makesAdded   makes this call inserted
     * @param modelsAdded  models this call inserted
     */
    private record CatalogueRows(Map<String, UUID[]> ids, int makesAdded, int modelsAdded) {
    }

    private CatalogueRows seedCatalogue() {
        Map<String, UUID[]> ids = new HashMap<>();
        int[] added = {0, 0};
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
                        added[0]++;
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
                            added[1]++;
                            return created;
                        });
                ids.put(definition.slug() + "/" + modelDefinition.slug(), new UUID[] {make.getId(), model.getId()});
            }
        }
        return new CatalogueRows(ids, added[0], added[1]);
    }

    private java.util.Optional<UUID> existingId(String sql, Object... arguments) {
        return jdbc.queryForList(sql, UUID.class, arguments).stream().findFirst();
    }

    private void seedListing(DemoCatalogue.Vehicle vehicle, UUID sellerId, UUID[] catalogueIds,
                             String description, DamageDeclaration damage, LeasingDeclaration offer) {
        CreateListingRequest request = new CreateListingRequest(
                vehicle.title(),
                description,
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
                DemoDetails.features(vehicle),
                damage,
                offer);

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

        // Drawn in its own body type already: nothing for correctListings to redraw.
        ledger.record(DemoLedger.DRAWING, listingId);

        listings.publish(sellerId, false, listingId);

        // The one direct write, and it is why this is a seeder and not a script against the
        // API: every listing published in the same second would make "plus récentes" an
        // arbitrary order. Spreading the dates is the only way to see sorting work.
        //
        // And already moderated, approved by the system: the queue is for what visitors
        // publish. No moderation_action row, for the reason given on approvePending.
        jdbc.update("""
                UPDATE listing SET moderation_status = 'APPROVED',
                                   published_at = now() - make_interval(days => ?)
                 WHERE id = ?""",
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
