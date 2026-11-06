import { AdminStats, Comparison, Count, PeriodWindowKeyEnum, Split } from '../../../core/api';
import { formatDateFr } from '../../../shared/format/date';
import { NBSP } from '../../../shared/format/number';

/**
 * What the dashboard does with the figures, apart from showing them: the periods, the demo
 * switch, trends, and the CSV. Pure functions, so each rule is tested without a page around it.
 */

export type PeriodKey = `${PeriodWindowKeyEnum}`;

export interface Period {
  readonly key: PeriodKey;
  /** In the address: `?periode=7j`. */
  readonly slug: string;
  readonly label: string;
  /** What each trend is against, said as the badge says it. */
  readonly versus: string;
}

export const PERIODS: readonly Period[] = [
  { key: 'TODAY', slug: 'jour', label: 'Aujourd’hui', versus: 'vs hier à la même heure' },
  { key: 'WEEK', slug: '7j', label: '7 jours', versus: 'vs les 7 jours précédents' },
  { key: 'MONTH', slug: '30j', label: '30 jours', versus: 'vs les 30 jours précédents' },
  { key: 'QUARTER', slug: 'trimestre', label: 'Trimestre', versus: 'vs le trimestre précédent' },
];

export const DEFAULT_PERIOD: Period = PERIODS[1];

/** The period an address names, or a week for anything else. */
export function periodFromSlug(slug: string | null): Period {
  return PERIODS.find((period) => period.slug === slug) ?? DEFAULT_PERIOD;
}

/** The demo is in unless the address says `demo=0`: on by default, as the brief has it. */
export function demoFromParam(value: string | null): boolean {
  return value !== '0';
}

/** A split count as the page shows it: real activity, with the demo seed added when it is on. */
export function pick(split: Split | undefined, demo: boolean): number {
  if (!split) {
    return 0;
  }
  return split.real + (demo ? split.demo : 0);
}

export interface Trend {
  /** `none`: nothing in either period. `new`: something now, nothing before, so no percentage. */
  readonly kind: 'up' | 'down' | 'flat' | 'new' | 'none';
  /** Rounded, signed; null when there is no percentage to give. */
  readonly percent: number | null;
  /** "+12 %", "−8 %", "Stable", "Nouveau", "—". */
  readonly label: string;
}

const MINUS = '−';

/** How a figure moved from the previous period: a change over the previous one, rounded. */
export function trend(current: number, previous: number): Trend {
  if (current === 0 && previous === 0) {
    return { kind: 'none', percent: null, label: '—' };
  }
  if (previous === 0) {
    return { kind: 'new', percent: null, label: 'Nouveau' };
  }
  const percent = Math.round(((current - previous) / previous) * 100);
  if (percent === 0) {
    return { kind: 'flat', percent: 0, label: 'Stable' };
  }
  const magnitude = new Intl.NumberFormat('fr-FR').format(Math.abs(percent));
  return {
    kind: percent > 0 ? 'up' : 'down',
    percent,
    label: `${percent > 0 ? '+' : MINUS}${magnitude}${NBSP}%`,
  };
}

/** Whether a move is good news. A rise is, except for what should go down: failures. */
export function toneOf(change: Trend, goodWhenUp = true): 'good' | 'bad' | 'neutral' {
  if (change.kind === 'up' || change.kind === 'new') {
    return goodWhenUp ? 'good' : 'bad';
  }
  if (change.kind === 'down') {
    return goodWhenUp ? 'bad' : 'good';
  }
  return 'neutral';
}

export function trendOfSplits(comparison: Comparison, demo: boolean): Trend {
  return trend(pick(comparison.current, demo), pick(comparison.previous, demo));
}

export function trendOfCount(count: Count): Trend {
  return trend(count.current, count.previous);
}

/** "depuis 25 min", "depuis 5 h", "depuis 3 j": how long something has waited. */
export function waitingFr(since: string | undefined | null, now = new Date()): string | null {
  const start = since ? new Date(since).getTime() : NaN;
  if (Number.isNaN(start)) {
    return null;
  }
  const minutes = Math.max(0, Math.floor((now.getTime() - start) / 60_000));
  if (minutes < 60) {
    return `depuis ${minutes}${NBSP}min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `depuis ${hours}${NBSP}h`;
  }
  return `depuis ${Math.floor(hours / 24)}${NBSP}j`;
}

/** The filters the results page counts, by the names the API counts them under. */
export const FILTER_LABELS: Readonly<Record<string, string>> = {
  text: 'Recherche libre',
  make: 'Marque',
  model: 'Modèle',
  year: 'Année',
  price: 'Prix',
  mileage: 'Kilométrage',
  fuelType: 'Carburant',
  gearbox: 'Boîte de vitesses',
  bodyType: 'Carrosserie',
  drivetrain: 'Transmission',
  condition: 'État',
  damage: 'Véhicules endommagés',
  location: 'Localisation',
  goodDeals: 'Bonnes affaires',
  leasing: 'Location (LOA, LLD)',
  leasingType: 'Type de location',
  monthly: 'Mensualité',
  duration: 'Durée de location',
  mileageAllowance: 'Kilométrage annuel',
};

export function filterLabel(name: string): string {
  return FILTER_LABELS[name] ?? name;
}

// ---- CSV ------------------------------------------------------------------------------

/**
 * The figures on the screen as a CSV for a French spreadsheet: semicolons, a byte-order mark so
 * the accents survive, and the demo switch applied as it is on the page.
 *
 * <p>A make is typed by sellers, so one that a spreadsheet would read as a formula is prefixed
 * with an apostrophe. The trends are written here, and "+12 %" is left as it is.
 */
export function buildCsv(stats: AdminStats, period: Period, demo: boolean): string {
  const rows: (string | number)[][] = [['Rubrique', 'Indicateur', 'Valeur', 'Période précédente', 'Évolution']];
  const compared = (section: string, name: string, comparison: Comparison) => {
    const current = pick(comparison.current, demo);
    const previous = pick(comparison.previous, demo);
    rows.push([section, name, current, previous, trend(current, previous).label]);
  };
  const counted = (section: string, name: string, count: Count) => {
    rows.push([section, name, count.current, count.previous, trend(count.current, count.previous).label]);
  };
  const now = (section: string, name: string, value: number) => rows.push([section, name, value, '', '']);

  rows.push(['Période', period.label, `${formatDateFr(stats.period.from)} – ${formatDateFr(stats.period.to)}`, period.versus, '']);
  rows.push(['Période', 'Données de démonstration', demo ? 'incluses' : 'exclues', '', '']);

  now('À traiter', 'Annonces à modérer', pick(stats.attention.pendingModeration, demo));
  now('À traiter', 'Signalements ouverts', stats.attention.openReports);

  compared('Activité', 'Nouvelles annonces', stats.activity.newListings);
  compared('Activité', 'Inscriptions', stats.activity.registrations);
  compared('Activité', 'Messages', stats.activity.messages);
  compared('Activité', 'Recherches enregistrées', stats.activity.savedSearches);

  counted('E-mails', 'Envoyés', stats.emails.sent);
  counted('E-mails', 'Échoués', stats.emails.failed);
  counted('E-mails', 'Alertes envoyées', stats.emails.alertsSent);

  counted('Recherche', 'Recherches (page de résultats)', stats.search.searches);
  counted('Recherche', 'Recherche intelligente : requêtes', stats.search.interpretRequests);
  counted('Recherche', 'Recherche intelligente : échecs', stats.search.interpretFailed);
  counted('Recherche', 'Recherche intelligente : en partie comprises', stats.search.interpretNotUnderstood);
  for (const filter of stats.search.topFilters) {
    rows.push(['Recherche', `Filtre : ${filterLabel(filter.filter)}`, filter.current, filter.previous,
      trend(filter.current, filter.previous).label]);
  }
  now('Recherche', 'Alertes actives', stats.search.activeAlerts);

  const listings = stats.listings;
  now('Annonces', 'En ligne', pick(listings.published, demo));
  now('Annonces', 'Dont en attente de modération', pick(listings.pendingModeration, demo));
  now('Annonces', 'Dont endommagées', pick(listings.damaged, demo));
  now('Annonces', 'Dont ventes', pick(listings.sale, demo));
  now('Annonces', 'Dont locations', pick(listings.leasing, demo));
  now('Annonces', 'Vendues', pick(listings.sold, demo));
  now('Annonces', 'Expirées', pick(listings.expired, demo));
  now('Annonces', 'Refusées', pick(listings.rejected, demo));

  for (const make of stats.topMakes) {
    now('Marques les plus publiées', typed(make.make), pick(make.published, demo));
  }

  now('Prix du marché', 'Bonne affaire', pick(stats.priceVerdicts.goodDeal, demo));
  now('Prix du marché', 'Prix du marché', pick(stats.priceVerdicts.market, demo));
  now('Prix du marché', 'Au-dessus du marché', pick(stats.priceVerdicts.aboveMarket, demo));
  now('Prix du marché', 'Sans indicateur', pick(stats.priceVerdicts.noVerdict, demo));

  const hourly = stats.period.bucket === 'HOUR';
  for (const point of stats.series) {
    const when = hourly ? point.start.slice(11, 16) : point.start.slice(0, 10);
    rows.push([hourly ? 'Par heure' : 'Par jour', `${when} – nouvelles annonces`, pick(point.newListings, demo), '', '']);
    rows.push([hourly ? 'Par heure' : 'Par jour', `${when} – inscriptions`, pick(point.registrations, demo), '', '']);
    rows.push([hourly ? 'Par heure' : 'Par jour', `${when} – messages`, pick(point.messages, demo), '', '']);
    rows.push([hourly ? 'Par heure' : 'Par jour', `${when} – recherches enregistrées`, pick(point.savedSearches, demo), '', '']);
    rows.push([hourly ? 'Par heure' : 'Par jour', `${when} – e-mails envoyés`, point.emailsSent, '', '']);
  }

  return '\uFEFF' + rows.map((row) => row.map(cell).join(';')).join('\r\n') + '\r\n';
}

/** "autox-tableau-de-bord-7j-2026-11-05.csv". */
export function csvFileName(period: Period, generatedAt: string): string {
  return `autox-tableau-de-bord-${period.slug}-${generatedAt.slice(0, 10)}.csv`;
}

/** Text a user typed: a spreadsheet runs a cell that starts like a formula, so it cannot start like one. */
function typed(value: string): string {
  return /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
}

function cell(value: string | number): string {
  if (typeof value === 'number') {
    return String(value);
  }
  return /[;"\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}
