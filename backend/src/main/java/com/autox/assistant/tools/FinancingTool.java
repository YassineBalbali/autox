package com.autox.assistant.tools;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code simulate_financing} tool: the listing page's simulator, and nothing beyond it
 * (docs/financing-simulator.md, "What it is not"). Same formula, same bounds, same example rate,
 * and the same rule on what it applies to: a sale, not damaged, with a price.
 *
 * <p>Every simulation carries its two mentions, as codes the clients render from the labels: the
 * consumer-credit warning and the note that it is an indicative simulation. They are part of the
 * result, so no answer can show figures without them.
 *
 * <p>The arguments are expected already checked (the plan's validator, A2): a value out of bounds
 * here is a programming error, and throws.
 */
@Component
public class FinancingTool {

    /** The web simulator's {@code EXAMPLE_RATE_PERCENT}; {@code FinancingToolTest} reads it from there. */
    public static final double EXAMPLE_RATE_PERCENT = 5.9;
    /** The web simulator's starting duration. */
    public static final int DEFAULT_MONTHS = 48;
    /** A price typed without a listing: the smart search's own bounds, 100 € to 10 000 000 €, in cents. */
    public static final long MIN_AMOUNT = 10_000L;
    public static final long MAX_AMOUNT = 1_000_000_000L;
    static final String CURRENCY = "EUR";

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantFinancingMention", enumAsRef = true)
    public enum Mention {
        /** « Un crédit vous engage et doit être remboursé… » (Code de la consommation, L. 312-5). */
        CREDIT_WARNING,
        /** An indicative simulation, not a credit offer, insurance and fees left out. */
        INDICATIVE_SIMULATION
    }

    private static final List<Mention> MENTIONS = List.of(Mention.CREDIT_WARNING, Mention.INDICATIVE_SIMULATION);

    private final ListingFactsReader reader;

    public FinancingTool(ListingFactsReader reader) {
        this.reader = reader;
    }

    /**
     * @param downPayment in minor units; null is none
     * @param months      12 to 84, a whole number of years; null is {@link #DEFAULT_MONTHS}
     * @param ratePercent 0 to 30; null is the example rate, flagged as such
     */
    public ToolResult<Simulation> forListing(UUID listingId, Long downPayment, Integer months, Double ratePercent) {
        Optional<ListingFacts> found = reader.find(listingId);
        if (found.isEmpty()) {
            return ToolResult.refusal(ToolResult.Reason.LISTING_UNAVAILABLE, listingId);
        }
        ListingFacts listing = found.get();
        if (!listing.financeable()) {
            return ToolResult.refusal(ToolResult.Reason.NOT_FINANCEABLE, listingId);
        }
        return simulate(listingId, listing.priceAmount(), listing.currencyCode(), downPayment, months, ratePercent);
    }

    /** A price the user gave, in euro cents, for a car that is not a listing. */
    public ToolResult<Simulation> forAmount(long priceAmount, Long downPayment, Integer months, Double ratePercent) {
        if (priceAmount < MIN_AMOUNT || priceAmount > MAX_AMOUNT) {
            throw new IllegalArgumentException("Price out of bounds: " + priceAmount);
        }
        return simulate(null, priceAmount, CURRENCY, downPayment, months, ratePercent);
    }

    /** The comparison's figure: no down payment, the default duration, the example rate. */
    Optional<Simulation> example(ListingFacts listing) {
        if (!listing.financeable()) {
            return Optional.empty();
        }
        return simulate(listing.id(), listing.priceAmount(), listing.currencyCode(), null, null, null) instanceof
                ToolResult.Answer<Simulation> answer ? Optional.of(answer.value()) : Optional.empty();
    }

    private static ToolResult<Simulation> simulate(UUID listingId, long price, String currency,
                                                   Long downPayment, Integer months, Double ratePercent) {
        long down = downPayment == null ? 0 : downPayment;
        int duration = months == null ? DEFAULT_MONTHS : months;
        boolean example = ratePercent == null;
        double rate = example ? EXAMPLE_RATE_PERCENT : ratePercent;
        if (down < 0) {
            throw new IllegalArgumentException("Negative down payment: " + down);
        }
        if (duration < CreditFormula.MIN_MONTHS || duration > CreditFormula.MAX_MONTHS || duration % 12 != 0) {
            throw new IllegalArgumentException("Duration must be 12 to 84 months, by year: " + duration);
        }
        if (!(rate >= 0 && rate <= CreditFormula.MAX_ANNUAL_RATE_PERCENT)) {
            throw new IllegalArgumentException("Rate out of bounds: " + rate);
        }
        long borrowed = price - down;
        if (borrowed <= 0) {
            return ToolResult.refusal(ToolResult.Reason.NOTHING_TO_FINANCE, listingId);
        }
        CreditFormula.Credit credit = CreditFormula.simulate(borrowed, rate, duration).orElseThrow();
        return ToolResult.answer(new Simulation(listingId, price, down, borrowed, duration, rate, example,
                credit.monthly(), credit.totalRepaid(), credit.creditCost(), currency, MENTIONS));
    }

    /**
     * @param listingId    null for a price the user gave
     * @param rateIsExample the rate is the example, not one the user was quoted: the answer says so
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantFinancing")
    public record Simulation(UUID listingId, long priceAmount, long downPayment, long borrowedAmount, int months,
                             double annualRatePercent, boolean rateIsExample, long monthlyAmount,
                             long totalRepaid, long creditCost, String currencyCode, List<Mention> mentions) {
    }
}
