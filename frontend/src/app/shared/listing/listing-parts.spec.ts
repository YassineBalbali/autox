import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { NBSP, formatIntegerFr, visibleSpaces } from '../format/number';
import { FilterChip } from '../ui/filter-chip';
import { Price, formatPrice } from './price';
import { SpecLine } from './spec-line';

/**
 * The three pieces a listing is assembled from.
 *
 * <p>These are the components the look of the site rests on, and each carries a rule that
 * is easy to break quietly: a price divided by the wrong power of ten, a thousands
 * separator the font cannot draw, a spec line whose order shifts when a fact is missing.
 */
describe('number formatting', () => {
  it('groups thousands with a space the font can draw', () => {
    // Intl emits U+202F, the narrow no-break space, which this application's font renders
    // at close to zero width: "18 990" becomes "18990". A price that has lost its grouping
    // is one people misread by a factor of ten.
    expect(formatIntegerFr(62400)).toBe(`62${NBSP}400`);
    expect(formatIntegerFr(62400)).not.toContain(' ');
  });

  it('keeps the space unbreakable', () => {
    // An ordinary space would let "62" and "400 km" land on different lines.
    expect(visibleSpaces('1 234')).toBe('1 234');
  });
});

describe('formatPrice', () => {
  it('reads the amount as minor units', () => {
    expect(formatPrice(1899000, 'EUR')).toBe(`18${NBSP}990${NBSP}€`);
  });

  it('drops the decimals from a whole amount', () => {
    // How a car price is written. ",00" on every row of a results page is noise.
    expect(formatPrice(1899000, 'EUR')).not.toContain(',');
  });

  it('keeps them when they carry information', () => {
    expect(formatPrice(1899050, 'EUR')).toBe(`18${NBSP}990,50${NBSP}€`);
  });

  it('uses the exponent it is given, not an assumed 2', () => {
    // A zero-decimal currency. Dividing by 100 regardless would show a hundredth of the
    // price — the reason the API sends currencyMinorUnit with every country.
    expect(formatPrice(2500000, 'JPY', 0)).toContain(`2${NBSP}500${NBSP}000`);
  });

  it('never contains the narrow space', () => {
    expect(formatPrice(123456700, 'EUR')).not.toContain(' ');
  });
});

describe('Price', () => {
  @Component({
    imports: [Price],
    template: `<app-price [amountMinor]="amount()" [size]="size()" />`,
  })
  class Host {
    readonly amount = signal(1899000);
    readonly size = signal<'list' | 'detail'>('list');
  }

  it('renders the formatted amount', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent.trim()).toBe(`18${NBSP}990${NBSP}€`);
  });

  it('has a larger form for the one price on a listing', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.size.set('detail');
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.price--detail')).not.toBeNull();
  });
});

describe('SpecLine', () => {
  @Component({
    imports: [SpecLine],
    template: `
      <app-spec-line
        [year]="year()"
        [mileage]="mileage()"
        [fuel]="fuel()"
        gearbox="Manuelle"
        [powerHp]="130"
      />
    `,
  })
  class Host {
    readonly year = signal<number | null>(2021);
    readonly mileage = signal<number | null>(62400);
    readonly fuel = signal<string | null>('Diesel');
  }

  const items = (root: HTMLElement) =>
    Array.from(root.querySelectorAll('li')).map((li) => li.textContent?.trim());

  it('runs year, mileage, fuel, gearbox, power — always in that order', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    expect(items(fixture.nativeElement)).toEqual([
      '2021',
      `62${NBSP}400${NBSP}km`,
      'Diesel',
      'Manuelle',
      `130${NBSP}ch`,
    ]);
  });

  it('does not group the year', async () => {
    // A year is a label, not a quantity: "2 021" is wrong.
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    expect(items(fixture.nativeElement)[0]).toBe('2021');
  });

  it('skips a missing fact rather than showing a dash', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.mileage.set(null);
    fixture.componentInstance.fuel.set(null);
    await fixture.whenStable();

    expect(items(fixture.nativeElement)).toEqual(['2021', 'Manuelle', `130${NBSP}ch`]);
  });

  it('shows a mileage of zero, which is a fact and not an absence', async () => {
    // A new car. `if (mileage)` would drop it.
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.mileage.set(0);
    await fixture.whenStable();

    expect(items(fixture.nativeElement)).toContain(`0${NBSP}km`);
  });

  it('is a list, so a screen reader announces items and not the dots', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('ul')).not.toBeNull();
    // The separators are CSS; none is in the text.
    expect(fixture.nativeElement.textContent).not.toContain('·');
  });
});

describe('FilterChip', () => {
  @Component({
    imports: [FilterChip],
    template: `
      <app-filter-chip [active]="active()" [count]="1284" (toggled)="active.set($event)">
        Diesel
      </app-filter-chip>
    `,
  })
  class Host {
    readonly active = signal(false);
  }

  it('is a toggle button that says which state it is in', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;

    expect(button.getAttribute('aria-pressed')).toBe('false');

    button.click();
    await fixture.whenStable();

    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.classList).toContain('chip--active');
  });

  it('asks for the opposite state and leaves the decision to its owner', async () => {
    // It holds no state: which filters are active belongs in the URL, not in a chip.
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.active.set(true);
    await fixture.whenStable();

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.active()).toBe(false);
  });

  it('shows the result count beside the label', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.chip__count').textContent).toBe('1284');
  });
});
