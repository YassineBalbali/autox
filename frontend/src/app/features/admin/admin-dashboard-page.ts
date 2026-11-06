import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { distinctUntilChanged, map, switchMap, tap } from 'rxjs';

import { AdminService, AdminStats, Point, SavedSearchesService, Split } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { formatDateTimeFr } from '../../shared/format/date';
import { NBSP, formatIntegerFr } from '../../shared/format/number';
import { countFr } from '../../shared/format/plural';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons.data';
import { AttentionCard } from './dashboard/attention-card';
import { BarItem, BarList } from './dashboard/bar-list';
import { DemoSwitch } from './dashboard/demo-switch';
import { Column, ColumnChart } from './dashboard/column-chart';
import {
  PERIODS,
  Period,
  Trend,
  buildCsv,
  csvFileName,
  demoFromParam,
  filterLabel,
  periodFromSlug,
  pick,
  trend,
  trendOfCount,
  trendOfSplits,
  waitingFr,
} from './dashboard/dashboard-model';
import { DonutChart, Segment } from './dashboard/donut-chart';
import { EmptyState } from './dashboard/empty-state';
import { KpiCard } from './dashboard/kpi-card';
import { Moderated, PendingQueue } from './dashboard/pending-queue';
import { TrendBadge } from './dashboard/trend-badge';
import { VerdictBar, VerdictPart } from './dashboard/verdict-bar';

type State = 'loading' | 'error' | 'ready';
type SeriesKey = 'newListings' | 'registrations' | 'messages' | 'savedSearches' | 'emailsSent';

/** "6,7 %", or a dash when there is nothing to take a share of. */
export function shareFr(part: number, whole: number): string {
  if (whole <= 0) {
    return '–';
  }
  return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format((100 * part) / whole)}${NBSP}%`;
}

interface Kpi {
  readonly key: SeriesKey;
  readonly label: string;
  readonly icon: IconName;
  readonly value: number;
  readonly trend: Trend;
  readonly spark: readonly number[];
}

const SERIES: readonly { key: SeriesKey; label: string }[] = [
  { key: 'newListings', label: 'Nouvelles annonces' },
  { key: 'registrations', label: 'Inscriptions' },
  { key: 'messages', label: 'Messages' },
  { key: 'savedSearches', label: 'Recherches enregistrées' },
  { key: 'emailsSent', label: 'E-mails envoyés' },
];

const DAY = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short', timeZone: 'UTC' });
const DAY_LONG = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' });

/**
 * The landing page of the administration: what needs a moderator first, then how the site is used
 * over a chosen period, each figure against the period before it.
 *
 * <p>The period and the demo switch live in the address, so a view can be shared and survives a
 * reload. The API sends real activity and the demo seed apart; the switch only decides what is
 * added up here, so turning it on or off asks nothing of the server.
 *
 * <p>Every figure is one the API counted. Where there is nothing to count the panel says so, in
 * its own words, rather than drawing an empty chart.
 */
@Component({
  selector: 'app-admin-dashboard-page',
  imports: [
    RouterLink,
    Alert,
    Icon,
    AttentionCard,
    DemoSwitch,
    KpiCard,
    TrendBadge,
    ColumnChart,
    DonutChart,
    BarList,
    VerdictBar,
    EmptyState,
    PendingQueue,
  ],
  templateUrl: './admin-dashboard-page.html',
  styleUrl: './admin-dashboard-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminDashboardPage {
  private readonly api = inject(AdminService);
  private readonly savedSearches = inject(SavedSearchesService);
  private readonly errors = inject(ErrorMessageService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly periods = PERIODS;
  protected readonly series = SERIES;
  protected readonly number = formatIntegerFr;
  protected readonly share = shareFr;
  protected readonly count = countFr;
  protected readonly filterLabel = filterLabel;

  protected readonly period = toSignal(
    this.route.queryParamMap.pipe(map((params) => periodFromSlug(params.get('periode')))),
    { initialValue: periodFromSlug(this.route.snapshot.queryParamMap.get('periode')) },
  );
  private readonly demoParam = toSignal(
    this.route.queryParamMap.pipe(map((params) => demoFromParam(params.get('demo')))),
    { initialValue: demoFromParam(this.route.snapshot.queryParamMap.get('demo')) },
  );

  protected readonly state = signal<State>('loading');
  protected readonly failure = signal<string | null>(null);
  protected readonly stats = signal<AdminStats | null>(null);
  protected readonly chartSeries = signal<SeriesKey>('newListings');

  /** Moderated from here since the figures were counted, so the counts follow at once. */
  private readonly moderatedHere = signal<readonly Moderated[]>([]);

  /** Whether there is a demo seed at all: without one, the switch has nothing to switch. */
  protected readonly demoPresent = computed(() => this.stats()?.demoDataPresent === true);
  /** The demo seed is in the figures: on by default, and only if there is one. */
  protected readonly demo = computed(() => this.demoPresent() && this.demoParam());

  protected readonly generatedAt = computed(() => formatDateTimeFr(this.stats()?.generatedAt));

  // ---- what needs attention -------------------------------------------------------------

  protected readonly pending = computed(() => {
    const stats = this.stats();
    if (!stats) {
      return 0;
    }
    const done = this.moderatedHere().filter((each) => this.demo() || !each.demo).length;
    return Math.max(0, pick(stats.attention.pendingModeration, this.demo()) - done);
  });

  protected readonly pendingSince = computed(() => {
    const attention = this.stats()?.attention;
    if (!attention) {
      return null;
    }
    const candidates = [attention.oldestPendingReal, this.demo() ? attention.oldestPendingDemo : undefined]
      .filter((value): value is string => !!value)
      .sort();
    return waitingFr(candidates[0]);
  });

  protected readonly reportsSince = computed(() => waitingFr(this.stats()?.attention.oldestOpenReport));

  /** The queue's five oldest: real and demo with the switch on, real alone with it off. */
  protected readonly oldestPending = computed(() =>
    (this.stats()?.attention.oldestPending ?? []).filter((listing) => this.demo() || !listing.demo).slice(0, 5),
  );

  // ---- activity -------------------------------------------------------------------------

  protected readonly kpis = computed<Kpi[]>(() => {
    const stats = this.stats();
    if (!stats) {
      return [];
    }
    const demo = this.demo();
    const spark = (key: SeriesKey) => stats.series.map((point) => valueAt(point, key, demo));
    const activity = stats.activity;
    return [
      { key: 'newListings', label: 'Nouvelles annonces', icon: 'layers', value: pick(activity.newListings.current, demo), trend: trendOfSplits(activity.newListings, demo), spark: spark('newListings') },
      { key: 'registrations', label: 'Inscriptions', icon: 'users', value: pick(activity.registrations.current, demo), trend: trendOfSplits(activity.registrations, demo), spark: spark('registrations') },
      { key: 'messages', label: 'Messages', icon: 'message-circle', value: pick(activity.messages.current, demo), trend: trendOfSplits(activity.messages, demo), spark: spark('messages') },
      { key: 'savedSearches', label: 'Recherches enregistrées', icon: 'bell', value: pick(activity.savedSearches.current, demo), trend: trendOfSplits(activity.savedSearches, demo), spark: spark('savedSearches') },
      { key: 'emailsSent', label: 'E-mails envoyés', icon: 'mail', value: stats.emails.sent.current, trend: trendOfCount(stats.emails.sent), spark: spark('emailsSent') },
    ];
  });

  protected readonly columns = computed<Column[]>(() => {
    const stats = this.stats();
    if (!stats) {
      return [];
    }
    const key = this.chartSeries();
    const hourly = stats.period.bucket === 'HOUR';
    return stats.series.map((point) => {
      const split = splitAt(point, key);
      const date = new Date(`${point.start}Z`);
      return {
        label: hourly ? `${Number(point.start.slice(11, 13))}${NBSP}h` : DAY.format(date),
        title: hourly ? `${Number(point.start.slice(11, 13))}${NBSP}h – ${Number(point.start.slice(11, 13)) + 1}${NBSP}h` : DAY_LONG.format(date),
        real: split.real,
        demo: split.demo,
      };
    });
  });

  protected readonly chartHasData = computed(() =>
    this.columns().some((column) => column.real > 0 || (this.demo() && column.demo > 0)),
  );
  protected readonly chartLabel = computed(() => {
    const series = SERIES.find((each) => each.key === this.chartSeries())?.label ?? '';
    return `${series}, ${this.stats()?.period.bucket === 'HOUR' ? 'par heure' : 'par jour'}, ${this.period().label.toLowerCase()}`;
  });

  // ---- the catalogue --------------------------------------------------------------------

  protected readonly statusSegments = computed<Segment[]>(() => {
    const listings = this.stats()?.listings;
    if (!listings) {
      return [];
    }
    const demo = this.demo();
    return [
      { label: 'En ligne', value: pick(listings.published, demo), color: 'var(--dash-online)' },
      { label: 'Vendues', value: pick(listings.sold, demo), color: 'var(--dash-sold)' },
      { label: 'Expirées', value: pick(listings.expired, demo), color: 'var(--dash-expired)' },
      { label: 'Refusées', value: pick(listings.rejected, demo), color: 'var(--dash-rejected)' },
    ];
  });

  protected readonly catalogueEmpty = computed(() => this.statusSegments().every((segment) => segment.value === 0));

  protected readonly catalogueFacts = computed(() => {
    const listings = this.stats()?.listings;
    if (!listings) {
      return null;
    }
    const demo = this.demo();
    return {
      pending: pick(listings.pendingModeration, demo),
      damaged: pick(listings.damaged, demo),
      sale: pick(listings.sale, demo),
      leasing: pick(listings.leasing, demo),
    };
  });

  protected readonly makes = computed<BarItem[]>(() => {
    const demo = this.demo();
    return (this.stats()?.topMakes ?? [])
      .map((make) => ({ label: make.make, value: pick(make.published, demo), demo: demo ? make.published.demo : 0 }))
      .filter((item) => item.value > 0)
      .sort((a, b) => b.value - a.value);
  });

  protected readonly verdicts = computed<VerdictPart[]>(() => {
    const verdicts = this.stats()?.priceVerdicts;
    if (!verdicts) {
      return [];
    }
    const demo = this.demo();
    const parts = [
      { key: 'good' as const, label: 'Bonne affaire', value: pick(verdicts.goodDeal, demo) },
      { key: 'market' as const, label: 'Prix du marché', value: pick(verdicts.market, demo) },
      { key: 'above' as const, label: 'Au-dessus du marché', value: pick(verdicts.aboveMarket, demo) },
      { key: 'none' as const, label: 'Sans indicateur', value: pick(verdicts.noVerdict, demo) },
    ];
    const total = parts.reduce((sum, part) => sum + part.value, 0);
    return parts.map((part) => ({ ...part, text: shareFr(part.value, total) }));
  });

  protected readonly verdictsEmpty = computed(() => this.verdicts().every((part) => part.value === 0));

  // ---- search and e-mail ----------------------------------------------------------------

  protected readonly filters = computed<BarItem[]>(() => {
    const search = this.stats()?.search;
    if (!search) {
      return [];
    }
    return search.topFilters.map((filter) => ({
      label: filterLabel(filter.filter),
      value: filter.current,
      note: shareFr(filter.current, search.searches.current),
    }));
  });

  protected readonly failureRate = computed(() => {
    const search = this.stats()?.search;
    return search ? shareFr(search.interpretFailed.current, search.interpretRequests.current) : '–';
  });

  protected readonly trendOf = trendOfCount;
  protected readonly trendOfValues = trend;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.route.queryParamMap
      .pipe(
        map((params) => periodFromSlug(params.get('periode'))),
        distinctUntilChanged((a, b) => a.key === b.key),
        tap(() => {
          this.state.set(this.stats() ? 'ready' : 'loading');
          this.moderatedHere.set([]);
        }),
        switchMap((period) => this.api.stats({ period: period.key })),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe({
        next: (stats) => {
          this.stats.set(stats);
          this.moderatedHere.set([]);
          this.state.set('ready');
        },
        error: (error: unknown) => {
          this.failure.set(this.errors.translate(error).message);
          this.state.set('error');
        },
      });
  }

  protected load(): void {
    this.state.set('loading');
    this.api.stats({ period: this.period().key }).subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.moderatedHere.set([]);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.failure.set(this.errors.translate(error).message);
        this.state.set('error');
      },
    });
  }

  protected setPeriod(period: Period): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { periode: period.key === 'WEEK' ? null : period.slug },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected toggleDemo(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { demo: this.demo() ? '0' : null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected exportCsv(): void {
    const stats = this.stats();
    if (!stats) {
      return;
    }
    const blob = new Blob([buildCsv(stats, this.period(), this.demo())], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = csvFileName(this.period(), stats.generatedAt);
    link.click();
    URL.revokeObjectURL(url);
  }

  protected moderated(event: Moderated): void {
    this.moderatedHere.update((done) => [...done, event]);
  }

  // ---- the test digest ------------------------------------------------------------------

  protected readonly sendingDigest = signal(false);
  protected readonly digestResult = signal<string | null>(null);

  protected sendMyDigest(): void {
    this.sendingDigest.set(true);
    this.digestResult.set(null);
    this.savedSearches.sendMyDigest().subscribe({
      next: (result) => {
        this.sendingDigest.set(false);
        this.digestResult.set(
          result.written
            ? 'Résumé écrit : il part avec le prochain envoi, dans la minute.'
            : 'Rien à envoyer : aucune nouvelle annonce dans vos recherches, ou votre adresse n’est pas vérifiée.',
        );
      },
      error: (error: unknown) => {
        this.sendingDigest.set(false);
        this.digestResult.set(this.errors.messageFor(error));
      },
    });
  }
}

function splitAt(point: Point, key: SeriesKey): Split {
  return key === 'emailsSent' ? { real: point.emailsSent, demo: 0 } : point[key];
}

function valueAt(point: Point, key: SeriesKey, demo: boolean): number {
  return pick(splitAt(point, key), demo);
}
