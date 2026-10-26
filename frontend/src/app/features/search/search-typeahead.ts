import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { catchError, debounceTime, distinctUntilChanged, map, of, Subject, switchMap } from 'rxjs';

import { CatalogueService, CatalogueSuggestion, CatalogueSuggestionTypeEnum } from '../../core/api';
import { formatIntegerFr } from '../../shared/format/number';

/** How long typing must pause before suggestions are asked for. */
export const SUGGEST_DEBOUNCE_MS = 200;

/** Fewer characters match nearly every make; the API refuses them, so they are not sent. */
export const SUGGEST_MIN_LENGTH = 2;

/** One suggestion as shown: the label split where the typed prefix ends, to bold it. */
interface Shown {
  readonly suggestion: CatalogueSuggestion;
  readonly before: string;
  readonly matched: string;
  readonly after: string;
  readonly count: string;
}

/**
 * The search bar's text field, with makes and models suggested as you type.
 *
 * <p>A combobox in the WAI-ARIA sense: the field keeps the focus, the list is announced
 * through `aria-expanded` and `aria-activedescendant`, and the arrow keys move a highlight
 * rather than the focus. Enter on a highlighted entry picks it; Enter with nothing
 * highlighted is the form's own submit, a free-text search as before.
 *
 * <p>Picking is the point. A suggestion is not text to complete the field with: it is a
 * make, or a model, and picking it sets those filters and empties the text — "Audi A3" as
 * words would only search titles for them.
 *
 * <p>Asked for once typing pauses for {@link SUGGEST_DEBOUNCE_MS}, and through `switchMap`,
 * so a slow answer to "au" cannot replace the answer to "audi a" that came after it.
 */
@Component({
  selector: 'app-search-typeahead',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search-typeahead.html',
  styleUrl: './search-typeahead.scss',
})
export class SearchTypeahead {
  private readonly catalogue = inject(CatalogueService);

  readonly inputId = input.required<string>();
  readonly value = input.required<string>();
  readonly placeholder = input('');

  /** Every edit of the text, as the bar's draft holds it. */
  readonly valueChange = output<string>();
  /** A suggestion chosen, by click or by Enter. */
  readonly picked = output<CatalogueSuggestion>();

  protected readonly shown = signal<readonly Shown[]>([]);
  protected readonly open = signal(false);
  protected readonly active = signal(-1);

  private readonly typed$ = new Subject<string>();

  constructor() {
    const subscription = this.typed$
      .pipe(
        map(normalise),
        debounceTime(SUGGEST_DEBOUNCE_MS),
        distinctUntilChanged(),
        switchMap((typed) =>
          typed.length < SUGGEST_MIN_LENGTH
            ? of({ typed, suggestions: [] as CatalogueSuggestion[] })
            : this.catalogue.suggestions({ q: typed }).pipe(
                // A failure costs the suggestions, never the search: the field still works.
                catchError(() => of([] as CatalogueSuggestion[])),
                map((suggestions) => ({ typed, suggestions })),
              ),
        ),
      )
      .subscribe(({ typed, suggestions }) => {
        this.shown.set(suggestions.map((suggestion) => display(suggestion, typed)));
        this.active.set(-1);
      });
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  protected optionId(index: number): string {
    return `${this.inputId()}-option-${index}`;
  }

  protected listId(): string {
    return `${this.inputId()}-suggestions`;
  }

  protected expanded(): boolean {
    return this.open() && this.shown().length > 0;
  }

  protected onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.valueChange.emit(value);
    if (normalise(value).length < SUGGEST_MIN_LENGTH) {
      // At once, not after the pause: what is listed no longer matches anything typed.
      this.shown.set([]);
    }
    this.open.set(true);
    this.typed$.next(value);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.shown().length;
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        if (count === 0) {
          return;
        }
        event.preventDefault();
        const step = event.key === 'ArrowDown' ? 1 : -1;
        if (!this.open()) {
          this.open.set(true);
          this.active.set(step === 1 ? 0 : count - 1);
          return;
        }
        // Round, as a listbox does: down from the last is the first.
        const from = this.active() === -1 && step === -1 ? 0 : this.active();
        this.active.set((from + step + count) % count);
        return;
      }
      case 'Enter': {
        const highlighted = this.expanded() ? this.shown()[this.active()] : undefined;
        if (highlighted) {
          // Not the form's submit: this Enter means "this one".
          event.preventDefault();
          this.pick(highlighted.suggestion);
        } else {
          this.close();
        }
        return;
      }
      case 'Escape':
        if (this.expanded()) {
          // Closes the list and nothing else — a search field would otherwise clear itself.
          event.preventDefault();
          event.stopPropagation();
          this.close();
        }
        return;
    }
  }

  protected pick(suggestion: CatalogueSuggestion): void {
    this.close();
    this.shown.set([]);
    // Cancels a request still on its way: its answer is to a question no longer asked.
    this.typed$.next('');
    this.picked.emit(suggestion);
  }

  protected close(): void {
    this.open.set(false);
    this.active.set(-1);
  }
}

/** Spaces as the API counts them: none outside, single inside. Case and accents are its to fold. */
function normalise(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

function display(suggestion: CatalogueSuggestion, typed: string): Shown {
  const label = suggestion.label;
  // The label begins with the typed text ("audi a" → "Audi A3"), or the model's name does
  // ("a3" → the "A3" of "Audi A3"). Anything else is shown unbolded rather than guessed at.
  let start = 0;
  let length = matchedLength(label, typed);
  if (length === 0 && suggestion.type === CatalogueSuggestionTypeEnum.MODEL && suggestion.modelName) {
    start = label.length - suggestion.modelName.length;
    length = matchedLength(suggestion.modelName, typed);
  }
  const count = suggestion.listingCount;
  return {
    suggestion,
    before: label.slice(0, start),
    matched: label.slice(start, start + length),
    after: label.slice(start + length),
    count: `${formatIntegerFr(count)} ${count < 2 ? 'annonce' : 'annonces'}`,
  };
}

/**
 * How many characters at the start of `text` match `typed`, ignoring case, accents and
 * runs of spaces as the server does; 0 if `text` does not start with it. Counted in the
 * text's own characters, so "Citroën" typed "citroe" bolds "Citroë".
 */
export function matchedLength(text: string, typed: string): number {
  const wanted = fold(normalise(typed));
  if (wanted.length === 0) {
    return 0;
  }
  let folded = '';
  for (let index = 0; index < text.length; index++) {
    const char = fold(text[index]);
    // A run of spaces in the text counts once, as it does in the typed text.
    folded += char === ' ' && folded.endsWith(' ') ? '' : char;
    if (folded.length >= wanted.length) {
      return folded.startsWith(wanted) ? index + 1 : 0;
    }
  }
  return 0;
}

function fold(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/\s/g, ' ');
}
