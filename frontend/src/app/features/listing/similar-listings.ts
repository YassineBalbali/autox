import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, startWith, switchMap } from 'rxjs';

import { ListingsPublicService, PublicListingSummary } from '../../core/api';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { FUEL_LABELS, GEARBOX_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { ListingCard } from '../../shared/listing/listing-card';

/**
 * "Annonces similaires", at the foot of a listing: three to six other live listings, as the
 * same cards as the search results.
 *
 * <p>Which ones, and when there are none, is the API's decision (`GET
 * /listings/{id}/similar`): the same model first, then the same kind of car for the money,
 * and nothing at all rather than one or two weak matches. So this shows what it is given,
 * and an empty answer leaves no trace on the page, no heading over an empty space.
 *
 * <p>Fetched after the listing, not with it: it is the last thing on the page, and the
 * listing should not wait for it. A failure is silent for the same reason. The visitor came
 * for the listing, and an error about the suggestions under it would be noise.
 */
@Component({
  selector: 'app-similar-listings',
  imports: [ListingCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (listings().length) {
      <section class="similar" aria-labelledby="titre-similaires">
        <h2 id="titre-similaires" class="similar__title" i18n="@@similarTitle">Annonces similaires</h2>
        <ul class="similar__grid" role="list">
          @for (listing of listings(); track listing.id) {
            <li>
              <app-listing-card
                [listing]="listing"
                [headingLevel]="3"
                [minorUnit]="minorUnitOf(listing)"
                [fuelLabel]="fuelLabel(listing)"
                [gearboxLabel]="gearboxLabel(listing)"
              />
            </li>
          }
        </ul>
      </section>
    }
  `,
  styleUrl: './similar-listings.scss',
})
export class SimilarListings {
  private readonly api = inject(ListingsPublicService);
  private readonly reference = inject(ReferenceStore);

  /** The listing on the page. */
  readonly listingId = input.required<string>();

  protected readonly listings = toSignal(
    toObservable(this.listingId).pipe(
      // Cleared at once when the page moves to another listing, so the last one's
      // suggestions are not shown under the next.
      switchMap((id) =>
        this.api.similar({ id }).pipe(
          catchError(() => of([] as PublicListingSummary[])),
          startWith([] as PublicListingSummary[]),
        ),
      ),
    ),
    { initialValue: [] as PublicListingSummary[] },
  );

  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  protected minorUnitOf(listing: PublicListingSummary): number {
    return this.minorUnits()[listing.currencyCode ?? ''] ?? DEFAULT_MINOR_UNIT;
  }

  protected fuelLabel(listing: PublicListingSummary): string {
    return labelOf(FUEL_LABELS, listing.fuelType);
  }

  protected gearboxLabel(listing: PublicListingSummary): string {
    return labelOf(GEARBOX_LABELS, listing.gearbox);
  }
}
