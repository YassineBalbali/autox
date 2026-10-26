import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Params, RouterLink } from '@angular/router';

import { ResultCount } from '../../core/api';
import { formatIntegerFr } from '../../shared/format/number';
import { BodySilhouette } from '../../shared/ui/body-silhouette';
import { SilhouetteName } from '../../shared/ui/body-silhouettes.data';
import { Icon } from '../../shared/ui/icon';
import { SEARCH_PATH } from '../search/legacy-search-redirect';

/**
 * One shortcut on the home page: a link to the results with some filters set.
 *
 * <p>Three shapes. A make is a name and an arrow. A budget is a figure and its count. A
 * body type adds the car's silhouette above the label. The count is `undefined` while it is on its way
 * (a quiet placeholder holds its line, so the tile does not grow when it lands) and
 * `null` if it never arrives (no number at all, the link still works).
 */
@Component({
  selector: 'app-shortcut-tile',
  imports: [RouterLink, Icon, BodySilhouette],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './shortcut-tile.scss',
  template: `
    <a [class]="'tile tile--' + variant()" [routerLink]="searchPath" [queryParams]="params()">
      @if (silhouette(); as name) {
        <app-body-silhouette class="tile__silhouette" [name]="name" [width]="72" />
      }
      <span class="tile__label">{{ label() }}</span>
      @if (variant() === 'make') {
        <app-icon class="tile__arrow" name="arrow-right" [size]="16" />
      } @else {
        <span class="tile__count">
          @if (countText(); as text) {
            {{ text }}
          } @else if (count() === undefined) {
            <span class="tile__pending" aria-hidden="true"></span>
          }
        </span>
      }
    </a>
  `,
})
export class ShortcutTile {
  readonly label = input.required<string>();
  readonly params = input.required<Params>();
  readonly variant = input<'make' | 'figure' | 'category'>('figure');
  readonly silhouette = input<SilhouetteName | null>(null);
  readonly count = input<ResultCount | null | undefined>(undefined);

  protected readonly searchPath = SEARCH_PATH;

  /** "1 annonce", "16 annonces", "Plus de 10 000 annonces". */
  protected readonly countText = computed(() => {
    const count = this.count();
    if (!count || typeof count.count !== 'number') {
      return null;
    }
    const figure = `${count.capped ? 'Plus de ' : ''}${formatIntegerFr(count.count)}`;
    return `${figure} ${count.count < 2 && !count.capped ? 'annonce' : 'annonces'}`;
  });
}
