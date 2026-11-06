import { describe, expect, it } from 'vitest';

import { formatIntegerFr } from './number';
import { countFr, pluralFr } from './plural';

describe('pluralFr', () => {
  it('takes the singular for zero and one, the plural from two', () => {
    expect(pluralFr(0, 'annonce', 'annonces')).toBe('annonce');
    expect(pluralFr(1, 'annonce', 'annonces')).toBe('annonce');
    expect(pluralFr(2, 'annonce', 'annonces')).toBe('annonces');
    expect(pluralFr(1500, 'annonce', 'annonces')).toBe('annonces');
  });

  it('agrees a whole phrase, adjectives included', () => {
    expect(countFr(1, 'recherche enregistrée active', 'recherches enregistrées actives')).toBe(
      '1 recherche enregistrée active',
    );
    expect(countFr(3, 'recherche enregistrée active', 'recherches enregistrées actives')).toBe(
      '3 recherches enregistrées actives',
    );
  });

  it('groups thousands as French does', () => {
    expect(countFr(12500, 'annonce', 'annonces')).toBe(`${formatIntegerFr(12500)} annonces`);
    expect(formatIntegerFr(12500)).not.toBe('12500');
  });
});
