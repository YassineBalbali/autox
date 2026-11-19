package com.autox.assistant.tools;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The {@code compare_listings} tool: two or three listings side by side, and the differences the
 * server computes. It never says which car to buy (docs/assistant-plan.md, 3.4): it says which is
 * the cheapest, the least driven, the most recent, and what each would cost a month on the
 * simulator's example, and the user orders them as they asked.
 */
@Component
public class CompareListingsTool {

    public static final int MIN_LISTINGS = 2;
    public static final int MAX_LISTINGS = 3;

    private final ListingFactsReader reader;
    private final FinancingTool financing;

    public CompareListingsTool(ListingFactsReader reader, FinancingTool financing) {
        this.reader = reader;
        this.financing = financing;
    }

    /** @param listingIds two or three distinct listings; a gone one makes the whole comparison a refusal */
    public ToolResult<Comparison> compare(List<UUID> listingIds) {
        Set<UUID> distinct = new LinkedHashSet<>(listingIds);
        if (distinct.size() != listingIds.size() || distinct.size() < MIN_LISTINGS || distinct.size() > MAX_LISTINGS) {
            throw new IllegalArgumentException("Two or three distinct listings, not " + listingIds);
        }
        List<ListingFacts> listings = new java.util.ArrayList<>();
        for (UUID id : distinct) {
            Optional<ListingFacts> facts = reader.find(id);
            if (facts.isEmpty()) {
                return ToolResult.refusal(ToolResult.Reason.LISTING_UNAVAILABLE, id);
            }
            listings.add(facts.get());
        }
        return ToolResult.answer(new Comparison(listings, differences(listings)));
    }

    private Differences differences(List<ListingFacts> listings) {
        // Prices compare between sales in one currency; a lease's price is a cash price nobody pays.
        List<ListingFacts> sales = listings.stream().filter(l -> l.offer() == com.autox.listing.OfferType.SALE).toList();
        boolean onePriceScale = sales.size() >= 2
                && sales.stream().map(ListingFacts::currencyCode).distinct().count() == 1;
        List<ListingFacts> leases = listings.stream().filter(l -> l.leasing() != null).toList();

        List<MonthlyExample> monthly = listings.stream()
                .filter(ListingFacts::financeable)
                .map(l -> financing.example(l).map(sim -> new MonthlyExample(l.id(), sim.monthlyAmount())))
                .flatMap(Optional::stream)
                .toList();

        return new Differences(
                onePriceScale ? lowest(sales, ListingFacts::priceAmount) : null,
                onePriceScale ? spread(sales, ListingFacts::priceAmount) : null,
                lowest(listings, l -> (long) l.mileageKm()),
                spread(listings, l -> (long) l.mileageKm()),
                unique(listings, Comparator.comparingInt((ListingFacts l) -> l.year())
                        .thenComparingInt(l -> l.month() == null ? 6 : l.month()).reversed()),
                leases.size() >= 2 && leases.stream().map(ListingFacts::currencyCode).distinct().count() == 1
                        ? lowest(leases, l -> l.leasing().monthlyPayment()) : null,
                monthly);
    }

    /** The one listing with the lowest value; null on a tie, so nothing is called "the cheapest" wrongly. */
    private static UUID lowest(List<ListingFacts> listings, Function<ListingFacts, Long> value) {
        return unique(listings, Comparator.comparing(value));
    }

    private static UUID unique(List<ListingFacts> listings, Comparator<ListingFacts> order) {
        List<ListingFacts> sorted = listings.stream().sorted(order).toList();
        if (sorted.size() > 1 && order.compare(sorted.get(0), sorted.get(1)) == 0) {
            return null;
        }
        return sorted.getFirst().id();
    }

    private static Long spread(List<ListingFacts> listings, Function<ListingFacts, Long> value) {
        long max = listings.stream().map(value).max(Long::compare).orElseThrow();
        long min = listings.stream().map(value).min(Long::compare).orElseThrow();
        return max - min;
    }

    public record Comparison(List<ListingFacts> listings, Differences differences) {
    }

    /**
     * Each id is one of the compared listings; null when the comparison does not apply (fewer
     * than two sales in one currency for the price) or when two are equal.
     *
     * @param priceSpread   between the dearest and the cheapest sale, in minor units
     * @param mileageSpread between the most and the least driven, in km
     * @param monthlyAtExample each financeable sale's monthly payment on the simulator's defaults
     */
    public record Differences(UUID cheapest, Long priceSpread, UUID leastDriven, Long mileageSpread,
                              UUID mostRecent, UUID lowestMonthlyLease, List<MonthlyExample> monthlyAtExample) {
    }

    public record MonthlyExample(UUID listingId, long monthlyAmount) {
    }
}
