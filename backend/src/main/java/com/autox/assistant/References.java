package com.autox.assistant;

import com.autox.assistant.tools.ListingFacts;
import com.autox.assistant.tools.ListingFactsReader;
import com.autox.listing.OfferType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The listings a user can point at in a turn, numbered {@code L1}, {@code L2}… (docs/assistant-plan.md,
 * 1.3). The planner answers with a code and the server maps it back, so an id is never written by
 * the model, and a typo cannot point at another listing.
 *
 * <p>The ids come from the client's state and from the page the panel was opened on; each is read
 * again, so a listing gone offline is dropped, and an id that never existed too. A label is built
 * from catalogue fields and codes only: never the title, the city, or anything a seller typed.
 */
@Component
public class References {

    public static final int MAX = 10;

    private final ListingFactsReader reader;

    public References(ListingFactsReader reader) {
        this.reader = reader;
    }

    /**
     * @param shown       the ids the client says were shown, oldest first
     * @param pageListing the listing the panel was opened from, or null
     */
    public Table build(List<UUID> shown, UUID pageListing) {
        Set<UUID> ids = new LinkedHashSet<>(shown == null ? List.of() : shown);
        if (pageListing != null) {
            ids.remove(pageListing);
            ids.add(pageListing);
        }
        List<UUID> recent = new ArrayList<>(ids);
        if (recent.size() > MAX) {
            recent = recent.subList(recent.size() - MAX, recent.size());
        }
        List<Reference> references = new ArrayList<>();
        for (UUID id : recent) {
            Optional<ListingFacts> facts = reader.find(id);
            facts.ifPresent(found -> references.add(new Reference("L" + (references.size() + 1), id, label(found))));
        }
        String page = pageListing == null ? null : references.stream()
                .filter(reference -> reference.listingId().equals(pageListing))
                .map(Reference::code).findFirst().orElse(null);
        return new Table(List.copyOf(references), page);
    }

    /** "Peugeot 208, 2020, 40000 km, 10000 EUR, PETROL, MANUAL": what the planner needs to tell listings apart. */
    static String label(ListingFacts facts) {
        List<String> parts = new ArrayList<>();
        parts.add(facts.make() == null ? "marque hors catalogue"
                : facts.model() == null ? facts.make() : facts.make() + " " + facts.model());
        parts.add(String.valueOf(facts.year()));
        parts.add(facts.mileageKm() + " km");
        if (facts.offer() == OfferType.LEASING && facts.leasing() != null) {
            parts.add(facts.leasing().type() + " " + facts.leasing().monthlyPayment() / 100 + " " + facts.currencyCode() + "/mois");
        } else {
            parts.add(facts.priceAmount() / 100 + " " + facts.currencyCode());
        }
        if (facts.fuelType() != null) {
            parts.add(facts.fuelType());
        }
        if (facts.gearbox() != null) {
            parts.add(facts.gearbox());
        }
        if (facts.damaged()) {
            parts.add("DAMAGED");
        }
        return String.join(", ", parts);
    }

    public record Reference(String code, UUID listingId, String label) {
    }

    /** @param pageListing the code of the listing the panel was opened from, or null */
    public record Table(List<Reference> references, String pageListing) {

        public Optional<UUID> resolve(String code) {
            return references.stream().filter(reference -> reference.code().equals(code))
                    .map(Reference::listingId).findFirst();
        }

        public List<UUID> ids() {
            return references.stream().map(Reference::listingId).toList();
        }
    }
}
