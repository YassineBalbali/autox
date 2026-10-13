import { ModerationActionType, ModerationReason, ModerationStatus } from '../../core/api';

/**
 * Moderation, in French. Shared because two audiences read these words: a moderator
 * choosing a reason, and the seller being told it.
 *
 * <p>Each map is exhaustive — a `Record` over the generated enum — so a reason added to the
 * API stops this file compiling until somebody has written its sentence.
 */

/** Why a listing was refused or taken down. Written to be read by the seller it happened to. */
export const MODERATION_REASON_LABELS: Record<ModerationReason, string> = {
  [ModerationReason.SUSPECTED_FRAUD]: 'Suspicion de fraude',
  [ModerationReason.MISLEADING_INFORMATION]: 'Informations inexactes ou trompeuses',
  [ModerationReason.DUPLICATE]: 'Annonce en double',
  [ModerationReason.NOT_A_VEHICLE]: 'Ne concerne pas un véhicule à vendre',
  [ModerationReason.INAPPROPRIATE_PHOTOS]: 'Photos inadaptées',
  [ModerationReason.PROHIBITED_CONTENT]: 'Contenu contraire aux conditions d’utilisation',
  [ModerationReason.CONTACT_DETAILS_IN_TEXT]: 'Coordonnées dans le titre ou la description',
  [ModerationReason.OTHER]: 'Autre motif',
};

/** The reasons in the order a moderator is offered them: `OTHER` last, as its name suggests. */
export const MODERATION_REASONS: readonly ModerationReason[] = Object.keys(
  MODERATION_REASON_LABELS,
) as ModerationReason[];

/** The reason that says nothing by itself, and so requires a note. Mirrors the API's rule. */
export function reasonRequiresNote(reason: ModerationReason | '' | null | undefined): boolean {
  return reason === ModerationReason.OTHER;
}

/** A line of the trail: what was done, as a past participle agreeing with "annonce". */
export const MODERATION_ACTION_LABELS: Record<ModerationActionType, string> = {
  [ModerationActionType.APPROVE]: 'Approuvée',
  [ModerationActionType.REJECT]: 'Refusée',
  [ModerationActionType.REMOVE]: 'Retirée',
  [ModerationActionType.REINSTATE]: 'Réintégrée',
};

/** The verdict a listing currently carries. */
export const MODERATION_STATUS_LABELS: Record<ModerationStatus, string> = {
  [ModerationStatus.NOT_REVIEWED]: 'À vérifier',
  [ModerationStatus.APPROVED]: 'Approuvée',
  [ModerationStatus.REJECTED]: 'Refusée',
};
