import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { NBSP } from '../format/number';
import { SellerBadge } from './seller-badge';
import { SpecFacts, SpecFactsLayout } from './spec-facts';

@Component({
  imports: [SpecFacts],
  template: `
    <app-spec-facts
      [year]="year()"
      [mileage]="mileage()"
      [fuel]="fuel()"
      [electric]="electric()"
      [gearbox]="gearbox()"
      [powerHp]="power()"
      [layout]="layout()"
    />
  `,
})
class FactsHost {
  readonly year = signal<number | undefined>(2021);
  readonly mileage = signal<number | undefined>(62400);
  readonly fuel = signal<string | undefined>('Diesel');
  readonly electric = signal(false);
  readonly gearbox = signal<string | undefined>('Manuelle');
  readonly power = signal<number | undefined>(130);
  readonly layout = signal<SpecFactsLayout>('line');
}

/** A result's key facts, each behind its icon. */
describe('SpecFacts', () => {
  let fixture: ComponentFixture<FactsHost>;

  async function render(change: (host: FactsHost) => void = () => undefined): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    fixture = TestBed.createComponent(FactsHost);
    change(fixture.componentInstance);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const texts = (root: HTMLElement) =>
    Array.from(root.querySelectorAll('.facts__text')).map((text) => text.textContent);
  const icons = (root: HTMLElement) =>
    Array.from(root.querySelectorAll('app-icon')).map((icon) => icon.querySelector('svg') !== null);

  it('shows year, mileage, fuel, gearbox and power, in that order', async () => {
    const root = await render();

    expect(texts(root)).toEqual(['2021', `62${NBSP}400${NBSP}km`, 'Diesel', 'Manuelle', `130${NBSP}ch`]);
    expect(icons(root)).toEqual([true, true, true, true, true]);
  });

  it('names each figure for a screen reader, since the icon says it only to the eye', async () => {
    const root = await render();
    const labels = Array.from(root.querySelectorAll('.facts__item .visually-hidden')).map((label) =>
      label.textContent?.replace(NBSP, ' ').trim(),
    );

    expect(labels).toEqual(['Année :', 'Kilométrage :', 'Énergie :', 'Boîte :', 'Puissance :']);
    // And the icons themselves are not announced.
    expect(root.querySelectorAll('svg[aria-hidden="true"]')).toHaveLength(5);
  });

  it('skips a missing fact rather than showing a dash', async () => {
    const root = await render((host) => {
      host.mileage.set(undefined);
      host.power.set(undefined);
    });

    expect(texts(root)).toEqual(['2021', 'Diesel', 'Manuelle']);
    expect(root.textContent).not.toContain('undefined');
  });

  it('keeps a mileage of zero: a new car has one', async () => {
    expect(texts(await render((host) => host.mileage.set(0)))).toContain(`0${NBSP}km`);
  });

  it('gives an electric car a plug instead of a pump', async () => {
    const pump = (await render()).querySelectorAll('svg path');
    const pumpShape = Array.from(pump).map((path) => path.getAttribute('d')).join();
    const plug = (await render((host) => host.electric.set(true))).querySelectorAll('svg path');

    expect(Array.from(plug).map((path) => path.getAttribute('d')).join()).not.toBe(pumpShape);
  });

  it('lays the facts out two by two in a card', async () => {
    const root = await render((host) => host.layout.set('grid'));

    expect(root.querySelector('.facts')?.classList).toContain('facts--grid');
  });
});

@Component({
  imports: [SellerBadge],
  template: `<app-seller-badge [role]="role()" />`,
})
class BadgeHost {
  readonly role = signal<string | undefined>(undefined);
}

/** Who is selling: both kinds are said, an unknown one is not guessed. */
describe('SellerBadge', () => {
  async function render(role: string | undefined): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    const fixture = TestBed.createComponent(BadgeHost);
    fixture.componentInstance.role.set(role);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('marks a professional', async () => {
    const badge = (await render('PROFESSIONNEL')).querySelector('.badge') as HTMLElement;

    expect(badge.textContent?.trim()).toBe('Pro');
    expect(badge.classList).toContain('badge--pro');
  });

  it('marks a private seller, differently', async () => {
    const badge = (await render('PARTICULIER')).querySelector('.badge') as HTMLElement;

    expect(badge.textContent?.trim()).toBe('Particulier');
    expect(badge.classList).not.toContain('badge--pro');
  });

  it('spells it out where there is room', async () => {
    TestBed.resetTestingModule();
    const badge = TestBed.createComponent(SellerBadge);
    badge.componentRef.setInput('role', 'PROFESSIONNEL');
    badge.componentRef.setInput('full', true);
    await badge.whenStable();

    expect((badge.nativeElement as HTMLElement).textContent?.trim()).toBe('Professionnel');
  });

  it('shows nothing for a role it does not know', async () => {
    expect((await render(undefined)).querySelector('.badge')).toBeNull();
    expect((await render('ADMIN')).querySelector('.badge')).toBeNull();
  });
});
