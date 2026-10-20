import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { PriceIndicator, PriceLevel } from '../../core/api';
import { PriceBadge } from './price-badge';

@Component({
  imports: [PriceBadge],
  template: `<app-price-badge [indicator]="indicator()" />`,
})
class Host {
  readonly indicator = signal<PriceIndicator | null | undefined>(undefined);
}

/** The price verdict: a meter and a word, or nothing. */
describe('PriceBadge', () => {
  async function render(indicator: PriceIndicator | null | undefined): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.indicator.set(indicator);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const lit = (root: HTMLElement) =>
    Array.from(root.querySelectorAll('.badge__segment')).findIndex((segment) =>
      segment.classList.contains('badge__segment--on'),
    );

  it.each([
    [PriceLevel.GOOD_DEAL, 'Bonne affaire', 0],
    [PriceLevel.MARKET, 'Prix du marché', 1],
    [PriceLevel.ABOVE_MARKET, 'Au-dessus du marché', 2],
  ])('words %s as "%s" and lights segment %i of the three', async (level, label, segment) => {
    const root = await render({ level, medianPriceAmount: 1_000_000, comparableCount: 6 });

    expect(root.querySelector('.badge__label')?.textContent).toBe(label);
    expect(lit(root)).toBe(segment);
    expect(root.querySelector('.badge')?.getAttribute('data-level')).toBe(level);
  });

  it('hides the meter from a screen reader, which reads the words', async () => {
    const root = await render({ level: PriceLevel.GOOD_DEAL, medianPriceAmount: 1, comparableCount: 5 });

    expect(root.querySelector('.badge__meter')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('says nothing at all without a verdict', async () => {
    // Too few comparables, a new car: silence, not "Prix du marché".
    expect((await render(undefined)).querySelector('.badge')).toBeNull();
    expect((await render(null)).querySelector('.badge')).toBeNull();
    expect((await render({ medianPriceAmount: 1, comparableCount: 9 })).querySelector('.badge')).toBeNull();
  });

  it('ignores a level this build does not know rather than showing the code', async () => {
    const root = await render({ level: 'BARGAIN' as PriceLevel, medianPriceAmount: 1, comparableCount: 5 });

    expect(root.querySelector('.badge')).toBeNull();
  });
});
