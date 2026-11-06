import { describe, expect, it } from 'vitest';

import {
  DEFAULT_PERIOD,
  PERIODS,
  buildCsv,
  csvFileName,
  demoFromParam,
  periodFromSlug,
  pick,
  toneOf,
  trend,
  waitingFr,
} from './dashboard-model';
import { STATS } from './dashboard.fixture';

const split = (real: number, demo = 0) => ({ real, demo });


describe('the periods', () => {
  it('are four, a week by default, read from the address', () => {
    expect(PERIODS.map((period) => period.slug)).toEqual(['jour', '7j', '30j', 'trimestre']);
    expect(periodFromSlug('30j').key).toBe('MONTH');
    expect(periodFromSlug(null)).toBe(DEFAULT_PERIOD);
    expect(periodFromSlug('annee')).toBe(DEFAULT_PERIOD);
    expect(DEFAULT_PERIOD.key).toBe('WEEK');
  });
});

describe('trends', () => {
  it('give the change over the previous period, rounded, signed, in French', () => {
    expect(trend(12, 10)).toEqual({ kind: 'up', percent: 20, label: '+20 %' });
    expect(trend(6, 8)).toEqual({ kind: 'down', percent: -25, label: '−25 %' });
    expect(trend(2001, 1000).label).toBe('+100 %');
    expect(trend(1500, 100).label).toBe('+1 400 %');
  });

  it('say "new" rather than an infinite percentage, and nothing at all for nothing', () => {
    expect(trend(5, 0)).toEqual({ kind: 'new', percent: null, label: 'Nouveau' });
    expect(trend(0, 0)).toEqual({ kind: 'none', percent: null, label: '—' });
    expect(trend(0, 4)).toEqual({ kind: 'down', percent: -100, label: '−100 %' });
  });

  it('call a change too small to round to a point stable', () => {
    expect(trend(1001, 1000)).toEqual({ kind: 'flat', percent: 0, label: 'Stable' });
  });

  it('are good news when what should rise rises, and bad when failures do', () => {
    expect(toneOf(trend(12, 10))).toBe('good');
    expect(toneOf(trend(6, 8))).toBe('bad');
    expect(toneOf(trend(12, 10), false)).toBe('bad');
    expect(toneOf(trend(6, 8), false)).toBe('good');
    expect(toneOf(trend(0, 0))).toBe('neutral');
  });
});

describe('the demo switch', () => {
  it('is on unless the address says otherwise', () => {
    expect(demoFromParam(null)).toBe(true);
    expect(demoFromParam('1')).toBe(true);
    expect(demoFromParam('0')).toBe(false);
  });

  it('adds the demo seed to the real figure, or leaves it out', () => {
    expect(pick(split(5, 160), true)).toBe(165);
    expect(pick(split(5, 160), false)).toBe(5);
    expect(pick(undefined, true)).toBe(0);
  });
});

describe('waiting times', () => {
  const now = new Date('2026-11-05T10:00:00Z');

  it('are said in minutes, hours or days', () => {
    expect(waitingFr('2026-11-05T09:35:00Z', now)).toBe('depuis 25 min');
    expect(waitingFr('2026-11-05T05:00:00Z', now)).toBe('depuis 5 h');
    expect(waitingFr('2026-11-02T09:00:00Z', now)).toBe('depuis 3 j');
    expect(waitingFr(undefined, now)).toBeNull();
  });
});

describe('the CSV export', () => {
  const lines = (csv: string) => csv.replace(/^\uFEFF/, '').trim().split('\r\n');
  const row = (csv: string, name: string) => lines(csv).find((line) => line.split(';')[1] === name)?.split(';');

  it('is for a French spreadsheet: a byte-order mark, semicolons, CRLF and a header', () => {
    const csv = buildCsv(STATS, DEFAULT_PERIOD, true);

    expect(csv.startsWith('\uFEFFRubrique;Indicateur;Valeur;Période précédente;Évolution\r\n')).toBe(true);
    expect(csv.endsWith('\r\n')).toBe(true);
  });

  it('holds what the page shows, the demo seed in when the switch is on', () => {
    const csv = buildCsv(STATS, DEFAULT_PERIOD, true);

    expect(row(csv, 'Nouvelles annonces')).toEqual(['Activité', 'Nouvelles annonces', '26', '3', '+767 %']);
    expect(row(csv, 'Annonces à modérer')).toEqual(['À traiter', 'Annonces à modérer', '42', '', '']);
    expect(row(csv, 'Données de démonstration')?.[2]).toBe('incluses');
    expect(row(csv, 'Filtre : Carburant')).toEqual(['Recherche', 'Filtre : Carburant', '40', '30', '+33 %']);
  });

  it('and out when it is off', () => {
    const csv = buildCsv(STATS, DEFAULT_PERIOD, false);

    expect(row(csv, 'Nouvelles annonces')).toEqual(['Activité', 'Nouvelles annonces', '6', '3', '+100 %']);
    expect(row(csv, 'Annonces à modérer')?.[2]).toBe('2');
    expect(row(csv, 'En ligne')?.[2]).toBe('5');
    expect(row(csv, 'Données de démonstration')?.[2]).toBe('exclues');
  });

  it('has a line per bucket of the series', () => {
    const csv = buildCsv(STATS, DEFAULT_PERIOD, true);

    expect(row(csv, '2026-11-04 – nouvelles annonces')).toEqual(['Par jour', '2026-11-04 – nouvelles annonces', '6', '', '']);
    expect(row(csv, '2026-11-04 – e-mails envoyés')?.[2]).toBe('2');
  });

  it('cannot carry a formula from a make a seller typed, and quotes what needs quoting', () => {
    const csv = buildCsv(STATS, DEFAULT_PERIOD, true);

    expect(csv).toContain(`Marques les plus publiées;"'=HYPERLINK(""x"")";1;;`);
    expect(csv).not.toContain(';=HYPERLINK');
  });

  it('is named after the period and the day', () => {
    expect(csvFileName(DEFAULT_PERIOD, STATS.generatedAt)).toBe('autox-tableau-de-bord-7j-2026-11-05.csv');
  });
});
