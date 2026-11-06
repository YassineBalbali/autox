import { AdminStats } from '../../../core/api';

/** Figures as the API sends them, for the dashboard's tests: real and demo apart, a week. */
const split = (real: number, demo = 0) => ({ real, demo });
const compared = (current: [number, number], previous: [number, number]) => ({
  current: split(...current),
  previous: split(...previous),
});
const count = (current: number, previous: number) => ({ current, previous });

export const STATS: AdminStats = {
  generatedAt: '2026-11-05T09:30:00Z',
  demoDataPresent: true,
  period: {
    key: 'WEEK' as AdminStats['period']['key'],
    from: '2026-10-29T23:00:00Z',
    to: '2026-11-05T09:30:00Z',
    previousFrom: '2026-10-22T22:00:00Z',
    previousTo: '2026-10-29T09:30:00Z',
    bucket: 'DAY' as AdminStats['period']['bucket'],
  },
  attention: {
    pendingModeration: split(2, 40),
    oldestPendingReal: '2026-11-02T09:30:00Z',
    oldestPendingDemo: '2026-10-25T09:30:00Z',
    openReports: 1,
    oldestOpenReport: '2026-11-05T07:30:00Z',
    oldestPending: [],
  },
  activity: {
    newListings: compared([6, 20], [3, 0]),
    registrations: compared([4, 10], [4, 2]),
    messages: compared([0, 0], [0, 0]),
    savedSearches: compared([2, 0], [0, 0]),
  },
  series: [
    { start: '2026-11-04T00:00:00', newListings: split(1, 5), registrations: split(1), messages: split(0), savedSearches: split(0), emailsSent: 2 },
    { start: '2026-11-05T00:00:00', newListings: split(2, 0), registrations: split(0), messages: split(1), savedSearches: split(1), emailsSent: 0 },
  ],
  listings: {
    published: split(5, 160),
    pendingModeration: split(2, 40),
    rejected: split(1),
    sold: split(3, 4),
    expired: split(0, 2),
    damaged: split(1, 8),
    sale: split(5, 148),
    leasing: split(0, 12),
  },
  topMakes: [
    { make: 'Peugeot', published: split(2, 40) },
    { make: '=HYPERLINK("x")', published: split(1, 0) },
  ],
  priceVerdicts: { goodDeal: split(1, 18), market: split(2, 38), aboveMarket: split(0, 14), noVerdict: split(2, 90) },
  search: {
    interpretRequests: count(15, 10),
    interpretFailed: count(1, 2),
    interpretNotUnderstood: count(3, 3),
    searches: count(120, 100),
    topFilters: [{ filter: 'fuelType', current: 40, previous: 30 }],
    activeAlerts: 1,
  },
  emails: { sent: count(12, 8), failed: count(0, 1), alertsSent: count(3, 0) },
};
