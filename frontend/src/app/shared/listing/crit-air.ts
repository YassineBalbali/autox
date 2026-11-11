import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { CritAir } from '../../core/api';

/**
 * The Crit'Air classes as the vignettes print them. The class itself is computed by the API from
 * the listing's fuel and first registration, by the official table (arrêté du 21 juin 2016,
 * annexe I, in the database function `crit_air_class`): this file only shows it.
 */
export const CRIT_AIR_NUMBER: Record<CritAir, number | null> = {
  [CritAir.ZERO]: 0,
  [CritAir.ONE]: 1,
  [CritAir.TWO]: 2,
  [CritAir.THREE]: 3,
  [CritAir.FOUR]: 4,
  [CritAir.FIVE]: 5,
  [CritAir.UNCLASSIFIED]: null,
};

/** In words, for the label beside the vignette and for a screen reader. */
export const CRIT_AIR_LABELS: Record<CritAir, string> = {
  [CritAir.ZERO]: 'Crit’Air 0 (électrique)',
  [CritAir.ONE]: 'Crit’Air 1',
  [CritAir.TWO]: 'Crit’Air 2',
  [CritAir.THREE]: 'Crit’Air 3',
  [CritAir.FOUR]: 'Crit’Air 4',
  [CritAir.FIVE]: 'Crit’Air 5',
  [CritAir.UNCLASSIFIED]: 'Non classé',
};

/** The filter's choices: "Crit'Air n ou mieux", 0 to 5. */
export const CRIT_AIR_MAX_OPTIONS: readonly { value: number; label: string }[] = [
  { value: 0, label: '0 (électrique)' },
  { value: 1, label: '1 ou mieux' },
  { value: 2, label: '2 ou mieux' },
  { value: 3, label: '3 ou mieux' },
  { value: 4, label: '4 ou mieux' },
  { value: 5, label: '5 ou mieux' },
];

/**
 * A listing's estimated Crit'Air: the vignette in its colour, what it is based on, and one line on
 * the low-emission zones. Renders nothing when the API gives no class: the fuel or the date could
 * not settle it, and nothing is better than a guess.
 */
@Component({
  selector: 'app-crit-air',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (critAir(); as value) {
      <div class="crit-air" [attr.data-crit-air]="value">
        <span class="crit-air__vignette" [attr.data-class]="number() ?? 'NC'" aria-hidden="true">
          @if (number() !== null) {
            <span class="crit-air__brand">Crit’Air</span>
            <span class="crit-air__number">{{ number() }}</span>
          } @else {
            <span class="crit-air__brand">Non classé</span>
          }
        </span>
        <div class="crit-air__text">
          <p class="crit-air__label">{{ label() }}</p>
          <p class="meta" i18n="@@critAirEstimated">Crit’Air estimée d’après l’énergie et la date de 1re immatriculation</p>
          <p class="meta" data-zfe i18n="@@critAirZfe">
            Dans les zones à faibles émissions (ZFE), chaque agglomération fixe les vignettes autorisées et les horaires :
            vérifiez les règles de la ville où vous roulez.
          </p>
        </div>
      </div>
    }
  `,
  styles: `
    .crit-air {
      display: flex;
      gap: var(--s-3);
      align-items: center;
      padding: var(--s-3);
      border: 1px solid var(--c-line);
      border-radius: var(--r-lg);
      background: var(--c-surface);

      p {
        margin: 0;
      }
    }

    // The vignettes' own colours: 0 green, 1 violet, 2 yellow, 3 orange, 4 burgundy, 5 grey.
    .crit-air__vignette {
      --vignette: #00a651;
      --vignette-ink: #ffffff;
      display: inline-flex;
      flex: none;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: var(--vignette);
      color: var(--vignette-ink);
      line-height: 1;

      &[data-class='1'] { --vignette: #7d4e9d; }
      &[data-class='2'] { --vignette: #fcd800; --vignette-ink: #0b1f3a; }
      &[data-class='3'] { --vignette: #f08a00; --vignette-ink: #0b1f3a; }
      &[data-class='4'] { --vignette: #8b1d2c; }
      &[data-class='5'] { --vignette: #6e6e6e; }
      &[data-class='NC'] {
        --vignette: var(--c-surface-sunk);
        --vignette-ink: var(--c-ink-soft);
        border: 1px dashed var(--c-line-strong);
      }
    }

    .crit-air__brand {
      font-size: 0.55rem;
      font-weight: var(--fw-semibold);
      text-align: center;
    }

    .crit-air__number {
      font-size: var(--t-lg);
      font-weight: var(--fw-heavy);
    }

    .crit-air__text {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      min-width: 0;
    }

    .crit-air__label {
      font-weight: var(--fw-semibold);
    }
  `,
})
export class CritAirBadge {
  readonly critAir = input<CritAir | null | undefined>(null);

  protected readonly number = computed(() => {
    const value = this.critAir();
    return value ? CRIT_AIR_NUMBER[value] : null;
  });
  protected readonly label = computed(() => {
    const value = this.critAir();
    return value ? CRIT_AIR_LABELS[value] : '';
  });
}
