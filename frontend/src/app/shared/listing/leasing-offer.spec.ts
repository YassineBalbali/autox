import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { LeasingType, LeasingView } from '../../core/api';
import { LeasingOffer } from './leasing-offer';

const words = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

const LOA: LeasingView = {
  type: LeasingType.LOA,
  monthlyPayment: 21_900,
  firstPayment: 250_000,
  durationMonths: 37,
  mileagePerYear: 10_000,
  optionPrice: 980_000,
  totalCost: 2_018_400,
};

const LLD: LeasingView = {
  type: LeasingType.LLD,
  monthlyPayment: 31_900,
  firstPayment: 0,
  durationMonths: 36,
  mileagePerYear: 15_000,
  totalCost: 1_116_500,
};

/**
 * The full offer on the listing page.
 *
 * <p>These words are what docs/leasing.md says the page carries, so a change here is a change to
 * that document too.
 */
describe('LeasingOffer', () => {
  async function render(leasing: LeasingView, priceAmount?: number): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(LeasingOffer);
    fixture.componentRef.setInput('leasing', leasing);
    fixture.componentRef.setInput('priceAmount', priceAmount);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const term = (root: HTMLElement, name: string) => root.querySelector(`[data-term="${name}"]`);
  const digits = (node: Element | null) => words(node).replace(/\s/g, '');

  it('shows everything an LOA comes to', async () => {
    const root = await render(LOA, 2_500_000);

    expect(words(term(root, 'monthly'))).toContain('Puis 36 loyers mensuels TTC de');
    expect(digits(term(root, 'monthly'))).toContain('219€');
    expect(digits(term(root, 'first'))).toContain('2500€');
    expect(words(term(root, 'duration'))).toContain('37 mois');
    expect(digits(term(root, 'mileage'))).toContain('10000km/an');
    expect(digits(term(root, 'cash-price'))).toContain('25000€');
    expect(digits(term(root, 'option'))).toContain('9800€');
    expect(words(term(root, 'total'))).toContain('Montant total dû en cas d’achat');
    expect(digits(term(root, 'total'))).toContain('20184€');
  });

  it('presents an LOA as a representative example, with the mention word for word', async () => {
    const root = await render(LOA);

    expect(words(root)).toContain('Exemple représentatif');
    expect(words(root.querySelector('[data-mention]'))).toBe(
      'Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager.',
    );
    expect(words(root.querySelector('[data-withdrawal]'))).toContain('14 jours');
  });

  it('presents an LLD as the rental it is: no option, no credit mention', async () => {
    const root = await render(LLD);

    expect(term(root, 'option')).toBeNull();
    expect(root.querySelector('[data-mention]')).toBeNull();
    expect(root.querySelector('[data-withdrawal]')).toBeNull();
    expect(words(root)).not.toContain('Exemple représentatif');
    expect(words(term(root, 'total'))).toContain('Coût total de la location');
    expect(words(root.querySelector('[data-lld-note]'))).toContain('restitué');
  });

  it('never works the total out itself', async () => {
    const root = await render({ ...LOA, totalCost: 123_400 });

    expect(digits(term(root, 'total'))).toContain('1234€');
  });
});
