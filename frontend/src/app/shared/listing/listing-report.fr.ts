import { ListingReportReason } from '../../core/api';
import LABELS from '../../../../labels.fr.json';

/**
 * The reasons a visitor can give for reporting a listing, as the form words them: from
 * frontend/labels.fr.json, in its order, shared with the Android app.
 */
export const LISTING_REPORT_REASONS: readonly { reason: ListingReportReason; label: string }[] =
  Object.entries(LABELS.enums.ListingReportReason).map(([reason, label]) => ({
    reason: reason as ListingReportReason,
    label,
  }));
