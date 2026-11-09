import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AdminStats, BASE_PATH, PendingListing } from '../../core/api';
import { AdminDashboardPage, shareFr } from './admin-dashboard-page';
import { STATS } from './dashboard/dashboard.fixture';

const BASE = 'http://api.test';
const STATS_URL = `${BASE}/api/v1/admin/stats`;

const pendingListing = (id: string, demo: boolean, createdAt = '2026-11-01T10:00:00Z'): PendingListing => ({
  id,
  label: `Peugeot 308 ${id}`,
  title: `Titre ${id}`,
  sellerName: 'Garage Martin',
  sellerRole: 'PROFESSIONNEL',
  createdAt,
  priceAmount: 1_899_000,
  currencyCode: 'EUR',
  leasing: false,
  damaged: false,
  demo,
});

const WITH_QUEUE: AdminStats = {
  ...STATS,
  attention: {
    ...STATS.attention,
    pendingModeration: { real: 2, demo: 3 },
    oldestPending: [
      pendingListing('d1', true, '2026-10-20T10:00:00Z'),
      pendingListing('d2', true, '2026-10-21T10:00:00Z'),
      pendingListing('r1', false, '2026-11-01T10:00:00Z'),
      pendingListing('r2', false, '2026-11-02T10:00:00Z'),
    ],
  },
};

const EMPTY: AdminStats = {
  ...STATS,
  demoDataPresent: false,
  attention: {
    pendingModeration: { real: 0, demo: 0 },
    openReports: 0,
    openListingReports: 0,
    oldestPending: [],
  },
  activity: {
    newListings: { current: { real: 0, demo: 0 }, previous: { real: 0, demo: 0 } },
    registrations: { current: { real: 0, demo: 0 }, previous: { real: 0, demo: 0 } },
    messages: { current: { real: 0, demo: 0 }, previous: { real: 0, demo: 0 } },
    savedSearches: { current: { real: 0, demo: 0 }, previous: { real: 0, demo: 0 } },
  },
  series: STATS.series.map((point) => ({
    ...point,
    newListings: { real: 0, demo: 0 },
    registrations: { real: 0, demo: 0 },
    messages: { real: 0, demo: 0 },
    savedSearches: { real: 0, demo: 0 },
    emailsSent: 0,
  })),
  listings: {
    published: { real: 0, demo: 0 },
    pendingModeration: { real: 0, demo: 0 },
    rejected: { real: 0, demo: 0 },
    sold: { real: 0, demo: 0 },
    expired: { real: 0, demo: 0 },
    damaged: { real: 0, demo: 0 },
    sale: { real: 0, demo: 0 },
    leasing: { real: 0, demo: 0 },
  },
  topMakes: [],
  priceVerdicts: {
    goodDeal: { real: 0, demo: 0 },
    market: { real: 0, demo: 0 },
    aboveMarket: { real: 0, demo: 0 },
    noVerdict: { real: 0, demo: 0 },
  },
  search: {
    interpretRequests: { current: 0, previous: 0 },
    interpretFailed: { current: 0, previous: 0 },
    interpretNotUnderstood: { current: 0, previous: 0 },
    searches: { current: 0, previous: 0 },
    topFilters: [],
    activeAlerts: 0,
  },
  emails: { sent: { current: 0, previous: 0 }, failed: { current: 0, previous: 0 }, alertsSent: { current: 0, previous: 0 } },
};

describe('AdminDashboardPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  afterEach(() => vi.restoreAllMocks());

  async function open(url = '/admin'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'admin', component: AdminDashboardPage },
          { path: 'admin/moderation', component: AdminDashboardPage },
          { path: 'admin/moderation/messages', component: AdminDashboardPage },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.routeNativeElement as HTMLElement;
  }

  const statsRequest = (): TestRequest => backend.expectOne((request) => request.url === STATS_URL);
  const settle = () => harness.fixture.whenStable();

  async function visit(stats: AdminStats, url = '/admin'): Promise<TestRequest> {
    await open(url);
    const request = statsRequest();
    request.flush(stats);
    await settle();
    return request;
  }

  const text = (selector: string) => (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const kpi = (key: string) => text(`[data-kpi="${key}"] .kpi__value`);

  describe('the period', () => {
    it('is a week by default, and asks for it', async () => {
      const request = await visit(STATS);

      expect(request.request.params.get('period')).toBe('WEEK');
      expect(root.querySelector('[data-period="7j"]')?.getAttribute('aria-checked')).toBe('true');
    });

    it('comes from the address, and a choice asks again and puts it there', async () => {
      const request = await visit(STATS, '/admin?periode=30j');
      expect(request.request.params.get('period')).toBe('MONTH');

      (root.querySelector('[data-period="jour"]') as HTMLButtonElement).click();
      await settle();

      expect(statsRequest().request.params.get('period')).toBe('TODAY');
    });

    it('says what each trend is against', async () => {
      await visit(STATS, '/admin?periode=trimestre');

      expect(text('[data-kpi="newListings"] .kpi__versus')).toBe('vs le trimestre précédent');
    });
  });

  describe('the demo switch', () => {
    it('is on by default and labelled so: the seed is in every figure', async () => {
      await visit(STATS);

      expect(root.querySelector('[data-action="toggle-demo"]')?.getAttribute('aria-checked')).toBe('true');
      expect(text('[data-demo-state]')).toBe('Démo incluse');
      expect(kpi('newListings')).toBe('26');
      expect(text('[data-attention="moderation"] .attention__figure')).toContain('42');
    });

    it('off, the figures are real activity alone, and asking the server nothing new', async () => {
      await visit(STATS, '/admin?demo=0');

      expect(root.querySelector('[data-action="toggle-demo"]')?.getAttribute('aria-checked')).toBe('false');
      expect(text('[data-demo-state]')).toBe('Activité réelle seulement');
      expect(kpi('newListings')).toBe('6');
      expect(text('[data-attention="moderation"] .attention__figure')).toContain('2');
      backend.expectNone((request) => request.url === STATS_URL);
    });

    it('switches without a new request', async () => {
      await visit(STATS);

      (root.querySelector('[data-action="toggle-demo"]') as HTMLButtonElement).click();
      await settle();

      expect(kpi('newListings')).toBe('6');
      backend.expectNone((request) => request.url === STATS_URL);
    });

    it('is disabled where there is no demo seed', async () => {
      await visit({ ...STATS, demoDataPresent: false });

      expect((root.querySelector('[data-action="toggle-demo"]') as HTMLButtonElement).disabled).toBe(true);
      expect(kpi('newListings')).toBe('6');
      expect(root.querySelector('[data-demo-state]')).toBeNull();
    });
  });

  describe('what needs attention', () => {
    it('stands out in colour when there is something, with a way to act on it', async () => {
      await visit(STATS);

      const moderation = root.querySelector('[data-attention="moderation"]') as HTMLElement;
      expect(moderation.classList).toContain('attention--warn');
      expect(moderation.querySelector('a')?.getAttribute('href')).toBe('/admin/moderation');
      const reports = root.querySelector('[data-attention="reports"]') as HTMLElement;
      expect(reports.classList).toContain('attention--danger');
      expect(text('[data-attention="reports"] .attention__figure')).toBe('2 annonces signalées · 1 message signalé');
      expect(reports.querySelector('a')?.getAttribute('href')).toBe('/admin/moderation/messages');
    });

    it('says plainly when there is nothing', async () => {
      await visit(EMPTY);

      expect(root.querySelector('[data-attention="moderation"]')?.classList).toContain('attention--ok');
      expect(text('[data-attention="moderation"] .attention__figure')).toBe('Rien à modérer');
      expect(text('[data-attention="reports"] .attention__figure')).toBe('Aucun signalement ouvert');
    });
  });

  describe('moderating from the dashboard', () => {
    it('lists the oldest five, demo ones only with the switch on', async () => {
      await visit(WITH_QUEUE);
      expect(Array.from(root.querySelectorAll('[data-pending]')).map((row) => row.getAttribute('data-pending'))).toEqual([
        'd1', 'd2', 'r1', 'r2',
      ]);
      expect(root.querySelectorAll('[data-demo-tag]')).toHaveLength(2);

      await visit(WITH_QUEUE, '/admin?demo=0');
      expect(Array.from(root.querySelectorAll('[data-pending]')).map((row) => row.getAttribute('data-pending'))).toEqual([
        'r1', 'r2',
      ]);
    });

    it('accepts one: the API is asked, the row goes and the count follows', async () => {
      await visit(WITH_QUEUE, '/admin?demo=0');

      (root.querySelector('[data-pending="r1"] [data-action="approve"]') as HTMLButtonElement).click();
      const request = backend.expectOne(`${BASE}/api/v1/admin/listings/r1/approve`);
      expect(request.request.method).toBe('POST');
      request.flush({});
      await settle();

      expect(root.querySelector('[data-pending="r1"]')).toBeNull();
      expect(text('[data-attention="moderation"] .attention__figure')).toBe('1 annonce à modérer');
    });

    it('refuses one with a reason, and asks for a note when the reason needs one', async () => {
      await visit(WITH_QUEUE, '/admin?demo=0');

      (root.querySelector('[data-pending="r2"] [data-action="reject"]') as HTMLButtonElement).click();
      await settle();
      const confirm = () => root.querySelector('[data-pending="r2"] [data-action="confirm-reject"]') as HTMLButtonElement;
      expect(confirm().disabled).toBe(true);

      const select = root.querySelector('[data-pending="r2"] [data-reason]') as HTMLSelectElement;
      select.value = 'OTHER';
      select.dispatchEvent(new Event('change'));
      await settle();
      expect(confirm().disabled).toBe(true);
      const note = root.querySelector('[data-pending="r2"] [data-note]') as HTMLInputElement;
      note.value = 'Annonce copiée d’un autre site';
      note.dispatchEvent(new Event('input'));
      await settle();
      expect(confirm().disabled).toBe(false);

      confirm().click();
      const request = backend.expectOne(`${BASE}/api/v1/admin/listings/r2/reject`);
      expect(request.request.body).toEqual({ reasonCode: 'OTHER', note: 'Annonce copiée d’un autre site' });
      request.flush({});
      await settle();

      expect(root.querySelector('[data-pending="r2"]')).toBeNull();
    });

    it('says why when the API refuses, and keeps the row', async () => {
      await visit(WITH_QUEUE, '/admin?demo=0');

      (root.querySelector('[data-pending="r1"] [data-action="approve"]') as HTMLButtonElement).click();
      backend.expectOne(`${BASE}/api/v1/admin/listings/r1/approve`).flush(
        { code: 'CONFLICT', detail: 'x' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(root.querySelector('[data-pending="r1"]')).not.toBeNull();
      expect(root.querySelector('[data-pending="r1"] [role="alert"]')).not.toBeNull();
    });

    it('has a designed empty state', async () => {
      await visit(EMPTY);

      expect(text('[data-empty="queue"]')).toContain('La file est vide');
    });
  });

  describe('the charts', () => {
    it('draw the chosen indicator over the period, demo stacked when it is on', async () => {
      await visit(STATS);

      expect(root.querySelectorAll('app-column-chart .chart__real')).toHaveLength(2);
      expect(root.querySelectorAll('app-column-chart .chart__demo')).toHaveLength(1);

      (root.querySelector('[data-series="emailsSent"]') as HTMLButtonElement).click();
      await settle();
      expect(root.querySelectorAll('app-column-chart .chart__real')).toHaveLength(1);
      expect(root.querySelectorAll('app-column-chart .chart__demo')).toHaveLength(0);
    });

    it('show a designed empty state, not an empty chart, where there is no data', async () => {
      await visit(EMPTY);

      for (const widget of ['activity', 'listings', 'makes', 'verdicts', 'filters']) {
        expect(root.querySelector(`[data-empty="${widget}"]`), widget).not.toBeNull();
      }
      expect(root.querySelector('app-column-chart')).toBeNull();
      expect(root.querySelector('app-donut-chart')).toBeNull();
    });
  });

  describe('counts in words', () => {
    it('agree with the number', async () => {
      await visit(STATS);
      expect(text('[data-stat="alerts"] strong')).toBe('1 active');
      expect(text('[data-fact="damaged"]')).toBe('9 endommagées');

      await visit({ ...STATS, search: { ...STATS.search, activeAlerts: 3 } }, '/admin?demo=0');
      expect(text('[data-stat="alerts"] strong')).toBe('3 actives');
      expect(text('[data-fact="damaged"]')).toBe('1 endommagée');
    });
  });

  describe('the export', () => {
    it('downloads what is on the screen as a CSV named after the period', async () => {
      await visit(STATS, '/admin?demo=0');
      const blobs: Blob[] = [];
      vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
        blobs.push(blob as Blob);
        return 'blob:test';
      });
      vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
      const clicked: HTMLAnchorElement[] = [];
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push(this);
      });

      (root.querySelector('[data-action="export"]') as HTMLButtonElement).click();

      expect(clicked).toHaveLength(1);
      expect(clicked[0].download).toBe('autox-tableau-de-bord-7j-2026-11-05.csv');
      const csv = await blobs[0].text();
      expect(csv).toContain('Activité;Nouvelles annonces;6;3;');
      expect(csv).toContain('Données de démonstration;exclues');
    });
  });

  it('shows it is loading, then, on failure, says so and can try again', async () => {
    await open();
    expect(text('.dash__state')).toContain('Chargement');

    statsRequest().flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
    await settle();
    (root.querySelector('.dash__state button') as HTMLButtonElement).click();
    statsRequest().flush(STATS);
    await settle();

    expect(kpi('newListings')).toBe('26');
  });

  it('is a French percentage, and a dash when there is nothing to divide by', () => {
    expect(shareFr(1, 3)).toBe('33,3 %');
    expect(shareFr(0, 0)).toBe('–');
  });
});
