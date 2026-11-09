import { AdminUserActionType, UserRole, UserStatus } from '../../core/api';

/** French words for accounts, in the administrators' screens. Exhaustive, so a new value stops this compiling. */
export const USER_ROLE_LABELS: Record<UserRole, string> = {
  [UserRole.PARTICULIER]: 'Particulier',
  [UserRole.PROFESSIONNEL]: 'Professionnel',
  [UserRole.ADMIN]: 'Administrateur',
};

export const USER_STATUS_LABELS: Record<UserStatus, string> = {
  [UserStatus.PENDING_VERIFICATION]: 'E-mail non vérifié',
  [UserStatus.ACTIVE]: 'Actif',
  [UserStatus.SUSPENDED]: 'Suspendu',
  [UserStatus.DELETED]: 'Fermé',
};

export const USER_ACTION_LABELS: Record<AdminUserActionType, string> = {
  [AdminUserActionType.SUSPEND]: 'Suspension',
  [AdminUserActionType.REACTIVATE]: 'Réactivation',
  [AdminUserActionType.CHANGE_ROLE]: 'Changement de rôle',
  [AdminUserActionType.HIDE_DEALER_PAGE]: 'Page professionnelle masquée',
  [AdminUserActionType.SHOW_DEALER_PAGE]: 'Page professionnelle rétablie',
};

/** What a dealer-page action records as its before and after. */
const DEALER_PAGE_LABELS: Record<string, string> = {
  VISIBLE: 'Page visible',
  HIDDEN: 'Page masquée',
};

export const USER_ROLES: readonly UserRole[] = [UserRole.PARTICULIER, UserRole.PROFESSIONNEL, UserRole.ADMIN];

/** A closed account is not in the list, so it is not something to filter by. */
export const FILTERABLE_USER_STATUSES: readonly UserStatus[] = [
  UserStatus.ACTIVE,
  UserStatus.PENDING_VERIFICATION,
  UserStatus.SUSPENDED,
];

/** A recorded value (a status or a role) in French, or as it is if it is neither. */
export function userValueLabel(value: string | null | undefined): string {
  const labels: Record<string, string> = { ...USER_ROLE_LABELS, ...USER_STATUS_LABELS, ...DEALER_PAGE_LABELS };
  return value ? (labels[value] ?? value) : '';
}
