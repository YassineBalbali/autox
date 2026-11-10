import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { CREDIT_WARNING, FinancingSimulator, parseAmount, parseRate } from './financing-simulator';

/** "Simuler un financement": the figures, the mentions, and nothing sent anywhere. */
describe('FinancingSimulator', () => {
  let fixture: ComponentFixture<FinancingSimulator>;
  let root: HTMLElement;

  const result = (name: string) =>
    root.querySelector(`[data-result="${name}"]`)?.textContent?.replace(/\s/g, ' ').replace(/ +/g, ' ').trim();

  async function show(price = 1_500_000): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(FinancingSimulator);
    fixture.componentRef.setInput('price', price);
    root = fixture.nativeElement;
    await fixture.whenStable();
  }

  async function set(name: string, value: string): Promise<void> {
    const control = root.querySelector(`[data-input="${name}"]`) as HTMLInputElement | HTMLSelectElement;
    control.value = value;
    control.dispatchEvent(new Event(control.tagName === 'SELECT' ? 'change' : 'input'));
    await fixture.whenStable();
  }

  it('starts from an example rate, said to be one and not an offer', async () => {
    await show();

    expect((root.querySelector('[data-input="rate"]') as HTMLInputElement).value).toBe('5,9');
    expect(root.querySelector('[data-example-rate]')?.textContent).toContain('Taux d’exemple (5,9 %), pas une offre de crédit');
  });

  it('gives the monthly payment, the total repaid and the cost of the credit', async () => {
    await show(1_700_000);
    await set('down-payment', '2 000');
    await set('months', '12');
    await set('rate', '6');

    // 15 000 € borrowed at 6 % over 12 months.
    expect(result('borrowed')).toBe('15 000 €');
    expect(result('monthly')).toBe('1 291 € /mois');
    expect(result('total')).toBe('15 491,96 €');
    expect(result('cost')).toBe('491,96 €');
  });

  it('works at 0 %', async () => {
    await show(1_200_000);
    await set('rate', '0');
    await set('months', '24');

    expect(result('monthly')).toBe('500 € /mois');
    expect(result('total')).toBe('12 000 €');
    expect(result('cost')).toBe('0 €');
  });

  it('always carries the legal warning and says it is not a credit offer', async () => {
    await show();

    expect(root.querySelector('[data-credit-warning]')?.textContent?.trim()).toBe(
      'Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager.',
    );
    expect(CREDIT_WARNING).toContain('Vérifiez vos capacités de remboursement');
    expect(root.querySelector('[data-not-an-offer]')?.textContent).toContain('Simulation indicative, sans valeur d’offre de crédit');
  });

  it('says what to correct instead of a figure, and offers 12 to 84 months', async () => {
    await show(1_000_000);
    await set('down-payment', '10000');
    expect(root.querySelector('[data-financing-error]')?.textContent).toContain('rien à financer');
    expect(root.querySelector('[data-result="monthly"]')).toBeNull();

    await set('down-payment', '0');
    await set('rate', '45');
    expect(root.querySelector('[data-financing-error]')?.textContent).toContain('entre 0 et 30 %');

    const months = Array.from(root.querySelectorAll('[data-input="months"] option')).map((o) => (o as HTMLOptionElement).value);
    expect(months).toEqual(['12', '24', '36', '48', '60', '72', '84']);
  });

  it('sends nothing: no request, no form to fill with contact details', async () => {
    await show();
    await set('down-payment', '3000');
    await set('rate', '4,5');

    TestBed.inject(HttpTestingController).verify();
    expect(root.querySelector('input[type="email"], input[type="tel"], form')).toBeNull();
  });

  it('reads amounts and rates as a French visitor types them', () => {
    expect(parseAmount('2 000')).toBe(2000);
    expect(parseAmount('')).toBe(0);
    expect(parseAmount('2,5')).toBeNull();
    expect(parseRate('5,9')).toBe(5.9);
    expect(parseRate('5.9')).toBe(5.9);
    expect(parseRate('abc')).toBeNull();
  });
});
