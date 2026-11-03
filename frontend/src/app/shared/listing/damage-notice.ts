import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { DamageView } from '../../core/api';
import { DAMAGE_TYPE_LABELS, labelOf } from './labels.fr';

/**
 * That a vehicle is damaged, and how: the badge, the type, whether it runs and the seller's
 * description. Placed ahead of the price wherever a damaged car is shown, because the price means
 * nothing without it. Renders nothing for an undamaged one.
 *
 * <p>On a result card the description is cut at three lines to keep the list a list, and the whole
 * text is on the listing's page. The badge, the type and whether it runs are never cut.
 *
 * <p>Whether it runs can be unknown, on a listing from before sellers were asked: then nothing is
 * said about it rather than a guess.
 */
@Component({
  selector: 'app-damage-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (damage(); as declared) {
      <div class="damage" [class.damage--compact]="compact()" data-damage>
        <p class="damage__line">
          <span class="tag tag--danger" i18n="@@damageBadge">Véhicule endommagé</span>
          <span class="damage__facts" data-damage-facts>{{ facts() }}</span>
        </p>
        <p class="damage__description" data-damage-description>{{ declared.description }}</p>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .damage {
      padding: var(--s-2);
      border-left: 3px solid var(--c-danger);
      border-radius: var(--r-sm);
      background: var(--c-danger-soft);
    }

    .damage__line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s-1) var(--s-2);
      margin: 0;
    }

    .damage__facts {
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      color: var(--c-ink);
    }

    .damage__description {
      margin: var(--s-1) 0 0;
      font-size: var(--t-sm);
      color: var(--c-ink);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }

    .damage--compact .damage__description {
      display: -webkit-box;
      overflow: hidden;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 3;
      line-clamp: 3;
      font-size: var(--t-xs);
    }
  `,
})
export class DamageNotice {
  readonly damage = input<DamageView | null | undefined>();
  /** For a result card. */
  readonly compact = input(false);

  /** "Carrosserie, grêle · Non roulant". */
  protected readonly facts = computed(() => {
    const declared = this.damage();
    const types = (declared?.types ?? []).map((type) => labelOf(DAMAGE_TYPE_LABELS, type)).join(', ');
    const running = declared?.running;
    const state = running === true ? 'Roulant' : running === false ? 'Non roulant' : null;
    return [types, state].filter(Boolean).join(' · ');
  });
}
