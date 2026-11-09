import { ListingReportReason } from '../../core/api';

/** The reasons a visitor can give for reporting a listing, as the form words them. */
export const LISTING_REPORT_REASONS: readonly { reason: ListingReportReason; label: string }[] = [
  { reason: ListingReportReason.SCAM, label: 'Arnaque' },
  { reason: ListingReportReason.MISLEADING, label: 'Annonce trompeuse' },
  { reason: ListingReportReason.INAPPROPRIATE, label: 'Contenu inapproprié' },
  { reason: ListingReportReason.OTHER, label: 'Autre' },
];
