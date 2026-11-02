import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { AdminStats, BASE_PATH } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { AdminDashboardPage, shareFr } from './admin-dashboard-page';
import { DayCount, dayBars } from './daily-chart';

const BASE = 'http://api.test';
const STATS = `${BASE}/api/v1/admin/stats`;

const split = (real: number, demo = 0) => ({ real, demo });

const days = (fill: (index: number) => { listings: number; demoListings?: number }) =>
  Array.from({ length: 30 }, (_, index) => {
    const { listings, demoListings = 0 } = fill(index);
    return {
      day: `2026-10-${String(index + 1).padStart(2, '0')}`,
      newListings: split(listings, demoListings),
      registrations: split(0),
    };
  });

const EMPTY: AdminStats = {
  generatedAt: '2026-11-02T10:00:00Z',
  demoDataPresent: false,
  listings: { published: split(0), pendingModeration: split(0), rejected: split(0), sold: split(0), expired: split(0) },
  last7Days: { newListings: split(0), registrations: split(0), messages: split(0) },
  last30Days: { newListings: split(0), registrations: split(0), messages: split(0) },
  daily: days(() => ({ listings: 0 })),
  topMakes: [],
  priceVerdicts: { goodDeal: split(0), market: split(0), aboveMarket: split(0), noVerdict: split(0) },
  searchLast7Days: { requests: 0, failed: 0, withNotUnderstood: 0 },
  searchLast30Days: { requests: 0, failed: 0, withNotUnderstood: 0 },
};

const SEEDED: AdminStats = {
  ...EMPTY,
  demoDataPresent: true,
  listings: {
    published: split(1, 152),
    pendingModeration: split(1, 40),
    rejected: split(2),
    sold: split(3, 4),
    expired: split(5),
  },
  last7Days: { newListings: split(1, 152), registrations: split(6, 12), messages: split(7) },
  last30Days: { newListings: split(8, 152), registrations: split(9, 12), messages: split(10) },
  daily: days((index) => (index === 29 ? { listings: 1, demoListings: 152 } : { listings: 0 })),
  topMakes: [{ make: 'Renault', published: split(1, 30) }, { make: 'Audi', published: split(0, 12) }],
  priceVerdicts: { goodDeal: split(1, 20), market: split(0, 90), aboveMarket: split(0, 22), noVerdict: split(0, 20) },
  searchLast7Days: { requests: 15, failed: 3, withNotUnderstood: 6 },
  searchLast30Days: { requests: 40, failed: 0, withNotUnderstood: 10 },
};

/** The page through a real router and a fake backend. */
describe('AdminDashboardPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const words = (element: Element | null | undefined) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const tile = (name: string) => root.querySelector(`[data-figure="${name}"]`);
  const figure = (name: string) => words(tile(name)?.querySelector('.dash__figure'));
  const demo = (name: string) => words(tile(name)?.querySelector('.dash__demo'));
  const rows = (table: string) =>
    Array.from(root.querySelectorAll(`[data-table="${table}"] tbody tr`)).map((row) =>
      Array.from(row.children).map((cell) => words(cell)),
    );

  async function open(): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'admin', component: AdminDashboardPage },
          { path: 'admin/moderation', component: AdminDashboardPage },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/admin');
    root = harness.routeNativeElement as HTMLElement;
  }

  async function visit(stats: AdminStats): Promise<void> {
    await open();
    backend.expectOne(STATS).flush(stats);
    await harness.fixture.whenStable();
  }

  it('shows it is loading, then, on failure, says so and can try again', async () => {
    await open();
    expect(words(root.querySelector('.dash__state'))).toBe('Chargement des chiffres…');

    backend.expectOne(STATS).flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(root.querySelector('app-alert')).not.toBeNull();

    (root.querySelector('.btn') as HTMLButtonElement).click();
    backend.expectOne(STATS).flush(EMPTY);
    await harness.fixture.whenStable();
    expect(figure('published')).toBe('0');
  });

  it('makes real activity the main figure, with the demo beside it', async () => {
    await visit(SEEDED);

    expect(figure('published')).toBe('1');
    expect(demo('published')).toBe('+ 152 démo');
    expect(figure('rejected')).toBe('2');
    expect(demo('rejected')).toBe('');
    expect(figure('sold')).toBe('3');
    expect(demo('sold')).toBe('+ 4 démo');
    expect(figure('expired')).toBe('5');
    expect(words(root.querySelector('header'))).toContain('Chiffre principal : l’activité réelle.');
  });

  it('links the pending count to the queue, which holds real and demo alike', async () => {
    await visit(SEEDED);

    expect(figure('pending')).toBe('1');
    const link = root.querySelector('[data-link="queue"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/admin/moderation');
    expect(words(link)).toBe('Voir la file (41)');
  });

  it('lists activity over 7 and 30 days', async () => {
    await visit(SEEDED);

    expect(rows('activity')).toEqual([
      ['Nouvelles annonces', '1 + 152 démo', '8 + 152 démo'],
      ['Inscriptions', '6 + 12 démo', '9 + 12 démo'],
      ['Messages envoyés', '7', '10'],
    ]);
  });

  it('lists makes and price verdicts, real and demo in their own columns', async () => {
    await visit(SEEDED);

    expect(rows('makes')).toEqual([['Renault', '1', '30'], ['Audi', '0', '12']]);
    expect(rows('verdicts')).toEqual([
      ['Bonne affaire', '1', '20'],
      ['Prix du marché', '0', '90'],
      ['Au-dessus du marché', '0', '22'],
      ['Sans verdict', '0', '20'],
    ]);
  });

  it('gives search failures as a share of requests, and not-understood as a share of those answered', async () => {
    await visit(SEEDED);

    expect(rows('search')).toEqual([
      ['Requêtes', '15', '40'],
      // words() folds the non-breaking space; shareFr's own test below keeps it.
      ['En échec (503)', '20 %', '0 %'],
      ['Avec une partie non comprise', '50 %', '25 %'],
    ]);
  });

  it('plots real activity by default, and adds the demo on request', async () => {
    await visit(SEEDED);
    const chart = root.querySelector('app-daily-chart') as HTMLElement;
    const maxLabel = () => words(chart.querySelector('.chart__max'));

    expect(chart.querySelector('.chart__real')).not.toBeNull();
    expect(chart.querySelector('.chart__demo')).toBeNull();
    expect(maxLabel()).toBe('1');

    const toggle = root.querySelector('.dash__toggle input') as HTMLInputElement;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();

    expect(chart.querySelector('.chart__demo')).not.toBeNull();
    expect(maxLabel()).toBe('153');
  });

  it('has an empty state for each part, and no demo wording without demo data', async () => {
    await visit(EMPTY);

    expect(figure('published')).toBe('0');
    expect(words(root.querySelector('[data-empty="makes"]'))).toBe('Aucune annonce publiée.');
    expect(words(root.querySelector('[data-empty="search"]'))).toBe('Aucune requête sur 30 jours.');
    expect(root.querySelector('[data-table="search"]')).toBeNull();
    expect(words(root.querySelector('.chart__empty'))).toBe('Rien sur ces 30 jours.');
    expect(root.querySelector('.dash__toggle')).toBeNull();
    expect(root.textContent).not.toContain('démo');
    expect(root.querySelectorAll('[data-table="verdicts"] thead th')).toHaveLength(2);
  });
});

describe('shareFr', () => {
  it('is a French percentage, and a dash when there is nothing to divide by', () => {
    expect(shareFr(1, 3)).toBe(`33,3${NBSP}%`);
    expect(shareFr(0, 5)).toBe(`0${NBSP}%`);
    expect(shareFr(0, 0)).toBe('–');
  });
});

describe('dayBars', () => {
  const series: DayCount[] = [
    { day: '2026-10-01', real: 0, demo: 0 },
    { day: '2026-10-02', real: 1, demo: 152 },
    { day: '2026-10-03', real: 4, demo: 0 },
  ];

  it('scales to the real figures when the demo is left out', () => {
    const { bars, max } = dayBars(series, false);

    expect(max).toBe(4);
    expect(bars[2].realHeight).toBe(80);
    expect(bars[1].realHeight).toBe(20);
    expect(bars[0].realHeight).toBe(0);
    expect(bars[1].demoHeight).toBe(0);
  });

  it('stacks the demo on the real and scales to the total when asked', () => {
    const { bars, max } = dayBars(series, true);

    expect(max).toBe(153);
    // A single real listing beside 152 demo ones still shows.
    expect(bars[1].realHeight).toBe(2);
    expect(bars[1].demoY + bars[1].demoHeight).toBe(bars[1].realY);
  });

  it('draws nothing for a month of nothing', () => {
    const { bars, max } = dayBars([{ day: '2026-10-01', real: 0, demo: 0 }], true);

    expect(max).toBe(0);
    expect(bars[0].realHeight).toBe(0);
  });
});
