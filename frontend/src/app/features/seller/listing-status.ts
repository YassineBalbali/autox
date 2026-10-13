import { ListingStatus, ListingSummary } from '../../core/api';

/**
 * A listing's state in words, and what its seller may do with it.
 */

/** Status to French label. Exhaustive: a state added to the API stops this compiling. */
export const STATUS_LABELS: Record<ListingStatus, string> = {
  [ListingStatus.DRAFT]: 'Brouillon',
  [ListingStatus.PENDING_REVIEW]: 'En attente de validation',
  [ListingStatus.ACTIVE]: 'En ligne',
  [ListingStatus.PAUSED]: 'En pause',
  [ListingStatus.SOLD]: 'Vendue',
  [ListingStatus.EXPIRED]: 'Expirée',
  [ListingStatus.REMOVED]: 'Supprimée',
};

/** The statuses a seller can filter their list by, in the order of a listing's life. */
export const FILTERABLE_STATUSES: readonly ListingStatus[] = [
  ListingStatus.DRAFT,
  ListingStatus.ACTIVE,
  ListingStatus.PAUSED,
  ListingStatus.SOLD,
  ListingStatus.EXPIRED,
];

export type ListingAction = 'edit' | 'publish' | 'pause' | 'renew' | 'sold' | 'delete';

/** Actions that cannot be undone, or not easily: these ask before they act. */
export const CONFIRMED_ACTIONS: ReadonlySet<ListingAction> = new Set(['sold', 'delete']);

export const ACTION_LABELS: Record<ListingAction, string> = {
  edit: 'Modifier',
  publish: 'Mettre en ligne',
  pause: 'Mettre en pause',
  renew: 'Renouveler',
  sold: 'Marquer comme vendue',
  delete: 'Supprimer',
};

/** The question asked before a confirmed action, and what saying yes will do. */
export const CONFIRMATIONS: Partial<Record<ListingAction, { question: string; confirm: string }>> = {
  sold: {
    question: 'Marquer cette annonce comme vendue ? Elle ne sera plus visible ni modifiable.',
    confirm: 'Oui, elle est vendue',
  },
  delete: {
    question: 'Supprimer définitivement cette annonce ?',
    confirm: 'Oui, supprimer',
  },
};

/**
 * What the seller may do with a listing, in the order the buttons appear.
 *
 * <p><b>Read from what the API says about the listing, not worked out from its status.</b>
 * `allowedTransitions`, `renewable` and `editable` are the server's own answer to "what
 * would you accept?", sent with every row. A table of rules copied here would agree with
 * the server until the day one of them changed — and then offer a button that answers 409.
 *
 * <p>Two of these are one transition seen from two states, and are told apart on purpose.
 * A draft or a paused listing going live is "mettre en ligne"; an expired one going live
 * is "renouveler", because that is the word for it and because it is a different endpoint
 * that also restarts the listing's clock.
 */
export function actionsFor(listing: ListingSummary): ListingAction[] {
  const can = (status: ListingStatus) => transitionsOf(listing).includes(status);
  const actions: ListingAction[] = [];

  if (listing.editable) {
    actions.push('edit');
  }
  if (can(ListingStatus.ACTIVE) && !listing.renewable) {
    actions.push('publish');
  }
  if (can(ListingStatus.PAUSED)) {
    actions.push('pause');
  }
  if (listing.renewable) {
    actions.push('renew');
  }
  if (can(ListingStatus.SOLD)) {
    actions.push('sold');
  }
  if (can(ListingStatus.REMOVED)) {
    actions.push('delete');
  }
  return actions;
}

/**
 * The allowed transitions as an array.
 *
 * <p>The generated model types this field as a `Set`, because the OpenAPI schema says
 * `uniqueItems`; what arrives over the wire is a JSON array, and nothing converts it.
 * Spreading works for both, so this is correct whichever of the two it really is — and
 * calling `.has()` on it, as the type invites, would throw at runtime.
 */
export function transitionsOf(listing: { allowedTransitions?: Iterable<ListingStatus> }): ListingStatus[] {
  return [...(listing.allowedTransitions ?? [])];
}
