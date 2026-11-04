import { LeasingType, LeasingView } from '../../core/api';

/**
 * Leasing in words. docs/leasing.md says what is shown, where it comes from, and that none of this
 * is legal advice.
 */

export const LEASING_TYPE_LABELS: Record<LeasingType, string> = {
  [LeasingType.LOA]: 'LOA',
  [LeasingType.LLD]: 'LLD',
};

/** Article L. 312-5 of the code de la consommation, word for word. Do not reword it. */
export const CREDIT_MENTION = 'Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager.';

/** Article L. 312-19, as the DGCCRF's fact sheet on LOA states it. */
export const LOA_WITHDRAWAL = 'Vous disposez d’un délai de rétractation de 14 jours à compter de l’acceptation du contrat.';

/** Service-public's description of an LLD, which is a rental rather than a credit. */
export const LLD_NOTE = 'Location sans option d’achat : le véhicule est restitué à la fin du contrat.';

/** "LOA · 37 mois · 10 000 km/an", the short form under a result's monthly payment. */
export function leasingTerms(leasing: LeasingView, formatKm: (km: number) => string): string {
  return [
    leasing.type ? LEASING_TYPE_LABELS[leasing.type] : null,
    `${leasing.durationMonths} mois`,
    `${formatKm(leasing.mileagePerYear ?? 0)} km/an`,
  ]
    .filter(Boolean)
    .join(' · ');
}

/** How many monthly payments follow the first one: the same sum the server's total is made of. */
export function paymentsAfterFirst(leasing: LeasingView): number {
  return (leasing.durationMonths ?? 1) - 1;
}
