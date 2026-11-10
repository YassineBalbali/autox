import { describe, expect, it } from 'vitest';

import { FINANCING_LIMITS, simulateCredit } from './financing';

/** The amortisation formula: amounts in cents, as the API gives prices. */
describe('simulateCredit', () => {
  it('gives the standard monthly payment, the total and the cost', () => {
    // 10 000 € over 48 months at 5 %: 230,29 € a month and 1 054,06 € of interest, the figures any loan table gives.
    const credit = simulateCredit({ principal: 1_000_000, annualRatePercent: 5, months: 48 });
    expect(credit).toEqual({ monthly: 23_029, totalRepaid: 1_105_406, creditCost: 105_406 });
  });

  it('works at 0 %: the amount divided by the months, and a credit that costs nothing', () => {
    expect(simulateCredit({ principal: 1_200_000, annualRatePercent: 0, months: 24 })).toEqual({
      monthly: 50_000,
      totalRepaid: 1_200_000,
      creditCost: 0,
    });
    // Not a round division: the payment is rounded, the total is still exactly the amount.
    expect(simulateCredit({ principal: 1_000_000, annualRatePercent: 0, months: 36 })).toEqual({
      monthly: 27_778,
      totalRepaid: 1_000_000,
      creditCost: 0,
    });
  });

  it('works on the shortest and the longest durations', () => {
    // 15 000 € at 6 %: 1 291,00 € over 12 months, 219,13 € over 84.
    const short = simulateCredit({ principal: 1_500_000, annualRatePercent: 6, months: 12 })!;
    expect(short.monthly).toBe(129_100);
    expect(short.creditCost).toBe(49_196);
    const long = simulateCredit({ principal: 1_500_000, annualRatePercent: 6, months: 84 })!;
    expect(long.monthly).toBe(21_913);
    expect(long.creditCost).toBe(340_678);
    // Longer is cheaper a month and dearer in all.
    expect(long.monthly).toBeLessThan(short.monthly);
    expect(long.creditCost).toBeGreaterThan(short.creditCost);
  });

  it('rounds the payment to the cent, and the total once, from the exact payment', () => {
    // 1 000 € at 7 % over 12 months: 86,5267… € a month.
    const credit = simulateCredit({ principal: 100_000, annualRatePercent: 7, months: 12 })!;
    expect(credit.monthly).toBe(8_653);
    expect(credit.totalRepaid).toBe(103_832);
    expect(credit.totalRepaid).not.toBe(credit.monthly * 12);
    expect(credit.creditCost).toBe(credit.totalRepaid - 100_000);
  });

  it('is not a loan when nothing is borrowed, or the figures are out of bounds', () => {
    const ok = { principal: 1_000_000, annualRatePercent: 5, months: 48 };
    expect(simulateCredit({ ...ok, principal: 0 })).toBeNull();
    expect(simulateCredit({ ...ok, principal: -1 })).toBeNull();
    expect(simulateCredit({ ...ok, annualRatePercent: -0.1 })).toBeNull();
    expect(simulateCredit({ ...ok, annualRatePercent: FINANCING_LIMITS.maxAnnualRatePercent + 1 })).toBeNull();
    expect(simulateCredit({ ...ok, annualRatePercent: Number.NaN })).toBeNull();
    expect(simulateCredit({ ...ok, months: 11 })).toBeNull();
    expect(simulateCredit({ ...ok, months: 85 })).toBeNull();
    expect(simulateCredit({ ...ok, months: 36.5 })).toBeNull();
  });
});
