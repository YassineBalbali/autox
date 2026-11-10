/**
 * A loan with constant monthly payments, as French consumer credit is quoted: the standard
 * amortisation formula, in the browser, on figures the visitor types. Nothing is sent anywhere.
 *
 * <p>All amounts in the currency's minor unit (cents), as the API gives prices.
 */

/** The bounds the simulator offers. 84 months is the usual ceiling for a car loan. */
export const FINANCING_LIMITS = {
  minMonths: 12,
  maxMonths: 84,
  /** A rate above this is not a car loan anyone could be offered: the simulator refuses it. */
  maxAnnualRatePercent: 30,
} as const;

export interface CreditInput {
  /** What is borrowed: the price less the down payment. */
  readonly principal: number;
  /** The nominal annual rate, in percent: 5.9 for 5.9 %. Zero is a free loan. */
  readonly annualRatePercent: number;
  readonly months: number;
}

export interface CreditSimulation {
  /** Each month, rounded to the nearest cent. */
  readonly monthly: number;
  /** All the payments together, rounded to the nearest cent. */
  readonly totalRepaid: number;
  /** What the credit costs: the total repaid less what was borrowed. Zero at 0 %. */
  readonly creditCost: number;
}

/**
 * The monthly payment, the total repaid and the cost of the credit.
 *
 * <p>With a monthly rate r = annual / 12 and n months, each payment is P·r / (1 − (1 + r)^−n);
 * at 0 % it is P / n. The payment is rounded to the cent for display; the total is the exact
 * payment times n, rounded once, so that at 0 % it is exactly what was borrowed and the cost is
 * zero, rather than a few cents either way from rounding each payment first. (A lender settles
 * those cents on the last payment.)
 *
 * <p>Returns null for what is not a loan: nothing borrowed, a negative amount or rate, a rate
 * above {@link FINANCING_LIMITS}, or a duration outside them or not a whole number of months.
 */
export function simulateCredit(input: CreditInput): CreditSimulation | null {
  const { principal, annualRatePercent, months } = input;
  if (
    !Number.isFinite(principal) ||
    !Number.isFinite(annualRatePercent) ||
    !Number.isInteger(months) ||
    principal <= 0 ||
    annualRatePercent < 0 ||
    annualRatePercent > FINANCING_LIMITS.maxAnnualRatePercent ||
    months < FINANCING_LIMITS.minMonths ||
    months > FINANCING_LIMITS.maxMonths
  ) {
    return null;
  }
  const rate = annualRatePercent / 100 / 12;
  const exactMonthly = rate === 0 ? principal / months : (principal * rate) / (1 - Math.pow(1 + rate, -months));
  const totalRepaid = Math.round(exactMonthly * months);
  return {
    monthly: Math.round(exactMonthly),
    totalRepaid,
    creditCost: totalRepaid - Math.round(principal),
  };
}
