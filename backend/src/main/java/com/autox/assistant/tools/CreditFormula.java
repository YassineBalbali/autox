package com.autox.assistant.tools;

import java.util.Optional;

/**
 * The financing simulator's formula, ported from the web's {@code simulateCredit}
 * (frontend/src/app/shared/listing/financing.ts) line for line, and held to the same test vectors
 * (frontend/fixtures/listing.json, {@code simulateCredit}) as the web and the app. Three copies of
 * one formula, one set of expected figures: docs/financing-simulator.md.
 *
 * <p>Amounts in the currency's minor unit. With a monthly rate r = annual / 12 and n months, the
 * payment is P·r / (1 − (1 + r)^−n), or P / n at 0 %. The payment is rounded to the cent; the total
 * is the exact payment times n, rounded once, so that at 0 % it is exactly the amount borrowed.
 */
public final class CreditFormula {

    public static final int MIN_MONTHS = 12;
    public static final int MAX_MONTHS = 84;
    /** A rate above this is not a car loan anyone could be offered. */
    public static final double MAX_ANNUAL_RATE_PERCENT = 30;

    private CreditFormula() {
    }

    /** Empty for what is not a loan: nothing borrowed, a negative or excessive rate, a duration out of bounds. */
    public static Optional<Credit> simulate(double principal, double annualRatePercent, int months) {
        if (!Double.isFinite(principal) || !Double.isFinite(annualRatePercent)
                || principal <= 0 || annualRatePercent < 0 || annualRatePercent > MAX_ANNUAL_RATE_PERCENT
                || months < MIN_MONTHS || months > MAX_MONTHS) {
            return Optional.empty();
        }
        double rate = annualRatePercent / 100 / 12;
        double exactMonthly = rate == 0 ? principal / months : principal * rate / (1 - Math.pow(1 + rate, -months));
        long totalRepaid = Math.round(exactMonthly * months);
        return Optional.of(new Credit(Math.round(exactMonthly), totalRepaid, totalRepaid - Math.round(principal)));
    }

    public record Credit(long monthly, long totalRepaid, long creditCost) {
    }
}
