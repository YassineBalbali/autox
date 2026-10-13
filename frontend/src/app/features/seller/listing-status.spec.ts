import { describe, expect, it } from 'vitest';

import { ListingStatus, ListingSummary } from '../../core/api';
import {
  ACTION_LABELS,
  CONFIRMATIONS,
  CONFIRMED_ACTIONS,
  FILTERABLE_STATUSES,
  STATUS_LABELS,
  actionsFor,
  transitionsOf,
} from './listing-status';

/**
 * What a seller may do with a listing.
 *
 * <p>The fixtures below are what the API sends for each state — the transition table lives
 * in `ListingStatus` on the backend and is asserted there. These tests are about reading
 * that answer faithfully, not about re-deriving it.
 */
describe('listing status', () => {
  /** A summary as it arrives: `allowedTransitions` is a JSON array, whatever the type says. */
  const summary = (
    status: ListingStatus,
    transitions: ListingStatus[],
    flags: { editable?: boolean; renewable?: boolean } = {},
  ): ListingSummary => ({
    id: 'a',
    status,
    allowedTransitions: transitions as unknown as Set<ListingStatus>,
    editable: flags.editable ?? false,
    renewable: flags.renewable ?? false,
  });

  const { DRAFT, ACTIVE, PAUSED, SOLD, EXPIRED, REMOVED } = ListingStatus;

  describe('actionsFor', () => {
    it('offers a draft: edit, put online, delete', () => {
      expect(actionsFor(summary(DRAFT, [ACTIVE, REMOVED], { editable: true }))).toEqual([
        'edit',
        'publish',
        'delete',
      ]);
    });

    it('offers an online listing everything except putting it online', () => {
      expect(
        actionsFor(summary(ACTIVE, [PAUSED, SOLD, EXPIRED, REMOVED], { editable: true, renewable: true })),
      ).toEqual(['edit', 'pause', 'renew', 'sold', 'delete']);
    });

    it('offers a paused listing a way back online', () => {
      expect(actionsFor(summary(PAUSED, [ACTIVE, SOLD, REMOVED], { editable: true }))).toEqual([
        'edit',
        'publish',
        'sold',
        'delete',
      ]);
    });

    it('calls an expired listing going back online "renew", never "publish"', () => {
      // One transition, two endpoints. Renewing restarts the clock; publishing an expired
      // listing is not something the API has.
      const actions = actionsFor(summary(EXPIRED, [ACTIVE, REMOVED], { editable: true, renewable: true }));
      expect(actions).toEqual(['edit', 'renew', 'delete']);
    });

    it('leaves a sold listing only its removal', () => {
      expect(actionsFor(summary(SOLD, [REMOVED]))).toEqual(['delete']);
    });

    it('offers nothing when the API allows nothing', () => {
      expect(actionsFor(summary(REMOVED, []))).toEqual([]);
      // A summary from an older API without the fields: no buttons rather than wrong ones.
      expect(actionsFor({ id: 'a', status: ACTIVE })).toEqual([]);
    });

    it('follows the API when it disagrees with what the status would suggest', () => {
      // The point of sending the transitions with each row. If the server one day stops
      // allowing a paused listing to be sold, the button goes with no change here.
      expect(actionsFor(summary(PAUSED, [ACTIVE], { editable: false }))).toEqual(['publish']);
    });
  });

  describe('transitionsOf', () => {
    it('reads the array the API really sends', () => {
      const listing = { allowedTransitions: [ACTIVE, REMOVED] as unknown as Set<ListingStatus> };
      expect(transitionsOf(listing)).toEqual([ACTIVE, REMOVED]);
    });

    it('reads a Set too, which is what the generated type claims', () => {
      expect(transitionsOf({ allowedTransitions: new Set([SOLD]) })).toEqual([SOLD]);
    });

    it('treats a missing field as no transitions', () => {
      expect(transitionsOf({})).toEqual([]);
    });
  });

  describe('the words', () => {
    it('names every status the API has', () => {
      for (const status of Object.values(ListingStatus)) {
        expect(STATUS_LABELS[status], status).toBeTruthy();
      }
    });

    it('uses the five labels the dashboard was asked for', () => {
      expect(FILTERABLE_STATUSES.map((status) => STATUS_LABELS[status])).toEqual([
        'Brouillon',
        'En ligne',
        'En pause',
        'Vendue',
        'Expirée',
      ]);
    });

    it('labels every action', () => {
      expect(ACTION_LABELS).toEqual({
        edit: 'Modifier',
        publish: 'Mettre en ligne',
        pause: 'Mettre en pause',
        renew: 'Renouveler',
        sold: 'Marquer comme vendue',
        delete: 'Supprimer',
      });
    });

    it('asks before the two actions that cannot be undone, and has a question for each', () => {
      expect([...CONFIRMED_ACTIONS].sort()).toEqual(['delete', 'sold']);
      for (const action of CONFIRMED_ACTIONS) {
        expect(CONFIRMATIONS[action]?.question, action).toMatch(/\?/);
        expect(CONFIRMATIONS[action]?.confirm, action).toBeTruthy();
      }
    });
  });
});
