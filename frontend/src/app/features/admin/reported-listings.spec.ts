import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ListingReportReason, ReportedListing, UserRole } from '../../core/api';
import { ReportedListings } from './reported-listings';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/admin/listing-reports`;

function report(id: string, overrides: Partial<ReportedListing> = {}): ReportedListing {
  return {
    reportId: id,
    reason: ListingReportReason.SCAM,
    note: 'Demande un virement à l’étranger.',
    reportedAt: '2026-11-08T09:00:00Z',
    listingId: 'l-' + id,
    title: 'Peugeot 308 BlueHDi 130',
    listingStatus: 'ACTIVE',
    priceAmount: 1899000,
    currencyCode: 'EUR',
    seller: { id: 's-1', displayName: 'Garage Bellevue', role: UserRole.PROFESSIONNEL },
    reporter: { id: 'u-1', displayName: 'Camille R.', role: UserRole.PARTICULIER },
    openOnListing: 1,
    ...overrides,
  };
}

/** The reported listings on the Signalements page: what a moderator reads, and the two decisions. */
describe('ReportedListings', () => {
  let backend: HttpTestingController;
  let root: HTMLElement;
  let settle: () => Promise<void>;

  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const page = (items: ReportedListing[]) => ({
    items,
    page: 0,
    size: 10,
    totalElements: items.length,
    totalPages: items.length ? 1 : 0,
    hasNext: false,
  });

  async function show(items: ReportedListing[]): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ReportedListings);
    root = fixture.nativeElement;
    settle = () => fixture.whenStable();
    await settle();
    open(LIST)[0].flush(page(items));
    await settle();
  }

  it('shows why, who reported it, the listing as it is now, and how many reports it has', async () => {
    await show([report('r1', { openOnListing: 2, leasingMonthly: 16900 })]);
    const card = root.querySelector('[data-report="listing"]') as HTMLElement;
    const words = card.textContent?.replace(/\s+/g, ' ');

    expect(card.querySelector('h3')?.textContent?.trim()).toBe('Arnaque');
    expect(words).toContain('Signalée par Camille R. le');
    expect(words).toContain('2 signalements sur cette annonce');
    expect(words).toContain('Demande un virement');
    // A leasing offer by its monthly payment, as everywhere else.
    // \s takes the no-break spaces the French format puts in a price.
    expect(words?.replace(/\s/g, ' ')).toContain('169 €/mois');
    expect(card.querySelector('a')?.getAttribute('href')).toBe('/annonces/l-r1');
    expect(root.querySelector('[data-count="listings"]')?.textContent?.trim()).toBe('1 en attente');
  });

  it('takes the listing down through its own endpoint, says so, and fetches the list again', async () => {
    await show([report('r1')]);

    (root.querySelector('[data-action="remove-listing"]') as HTMLButtonElement).click();
    await settle();
    const call = open(`${LIST}/r1/remove`);
    expect(call).toHaveLength(1);
    expect(call[0].request.method).toBe('POST');
    call[0].flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    open(LIST)[0].flush(page([]));
    await settle();

    expect(root.textContent).toContain('Annonce retirée');
    expect(root.querySelector('[data-empty="listings"]')).not.toBeNull();
  });

  it('dismisses through its own endpoint', async () => {
    await show([report('r1')]);

    (root.querySelector('[data-action="dismiss-listing"]') as HTMLButtonElement).click();
    await settle();

    expect(open(`${LIST}/r1/dismiss`)).toHaveLength(1);
  });

  it('offers only dismissal for a listing that is no longer online', async () => {
    await show([report('r1', { listingStatus: 'REMOVED' })]);

    expect(root.querySelector('[data-action="remove-listing"]')).toBeNull();
    expect(root.querySelector('[data-action="dismiss-listing"]')).not.toBeNull();
    expect(root.querySelector('[data-report="listing"] a')).toBeNull();
  });
});
