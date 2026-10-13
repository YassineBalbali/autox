import { describe, expect, it } from 'vitest';

import { ModerationActionType, ModerationReason, ModerationStatus } from '../../core/api';
import {
  MODERATION_ACTION_LABELS,
  MODERATION_REASONS,
  MODERATION_REASON_LABELS,
  MODERATION_STATUS_LABELS,
  reasonRequiresNote,
} from './moderation.fr';

/**
 * The words of moderation. One of these maps is read by the seller a decision was taken
 * against, so a missing or raw entry is not a cosmetic fault.
 */
describe('moderation labels', () => {
  it('has a sentence for every reason the API knows', () => {
    for (const reason of Object.values(ModerationReason)) {
      expect(MODERATION_REASON_LABELS[reason], reason).toBeTruthy();
      // A label, not the code with its underscores swapped for spaces.
      expect(MODERATION_REASON_LABELS[reason]).not.toMatch(/[A-Z]{3,}/);
    }
  });

  it('offers every reason once, with "autre" last', () => {
    expect([...MODERATION_REASONS].sort()).toEqual(Object.values(ModerationReason).sort());
    expect(MODERATION_REASONS.at(-1)).toBe(ModerationReason.OTHER);
  });

  it('requires a note for "autre" alone, as the API does', () => {
    expect(reasonRequiresNote(ModerationReason.OTHER)).toBe(true);
    for (const reason of Object.values(ModerationReason).filter((value) => value !== ModerationReason.OTHER)) {
      expect(reasonRequiresNote(reason), reason).toBe(false);
    }
    expect(reasonRequiresNote('')).toBe(false);
    expect(reasonRequiresNote(null)).toBe(false);
  });

  it('names every action of the trail and every verdict', () => {
    expect(MODERATION_ACTION_LABELS).toEqual({
      [ModerationActionType.APPROVE]: 'Approuvée',
      [ModerationActionType.REJECT]: 'Refusée',
      [ModerationActionType.REMOVE]: 'Retirée',
      [ModerationActionType.REINSTATE]: 'Réintégrée',
    });
    for (const status of Object.values(ModerationStatus)) {
      expect(MODERATION_STATUS_LABELS[status], status).toBeTruthy();
    }
  });
});
