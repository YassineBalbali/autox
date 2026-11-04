import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { OfferType } from '../../core/api';
import { OfferSwitch } from './offer-switch';

describe('OfferSwitch', () => {
  async function render(offer: OfferType) {
    const fixture = TestBed.createComponent(OfferSwitch);
    fixture.componentRef.setInput('offer', offer);
    const changes: OfferType[] = [];
    fixture.componentInstance.changed.subscribe((value) => changes.push(value));
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    const button = (value: OfferType) => root.querySelector(`[data-offer="${value}"]`) as HTMLButtonElement;
    return { changes, button };
  }

  it('says which search this is', async () => {
    const { button } = await render(OfferType.LEASING);

    expect(button(OfferType.LEASING).getAttribute('aria-pressed')).toBe('true');
    expect(button(OfferType.SALE).getAttribute('aria-pressed')).toBe('false');
    expect(button(OfferType.SALE).textContent?.trim()).toBe('Acheter');
    expect(button(OfferType.LEASING).textContent?.trim()).toBe('Louer');
  });

  it('reports a change, and nothing when the current one is pressed again', async () => {
    const { changes, button } = await render(OfferType.SALE);

    button(OfferType.SALE).click();
    expect(changes).toEqual([]);

    button(OfferType.LEASING).click();
    expect(changes).toEqual([OfferType.LEASING]);
  });
});
