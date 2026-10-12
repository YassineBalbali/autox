import { DestroyRef, Signal, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';

import { ErrorMessageService, UserFacingError } from '../../core/errors/error-message.service';

/**
 * The state every one of these forms needs: in flight, and what went wrong.
 *
 * <p>Six forms in this feature submit to the API, and each needs a pending flag to disable
 * its button, a translated banner, and the set of fields the API rejected. Written out per
 * screen that is the same twenty lines six times — and the line most likely to be left out
 * of the sixth copy is the one that clears a stale field error when the user edits the
 * field, which leaves a corrected field still showing as refused.
 *
 * <p>Create it with {@link submitState} from a field initialiser, where an injection
 * context exists.
 */
export class SubmitState {
  private readonly inFlight = signal(false);
  private readonly failure = signal<UserFacingError | null>(null);

  /** True between {@link begin} and whichever of {@link succeed}/{@link fail} follows. */
  readonly pending: Signal<boolean> = this.inFlight.asReadonly();

  /** The banner sentence, or `null`. Always from the French table, never the API's text. */
  readonly message: Signal<string | null>;

  /** Set on a 500, and worth showing: it is the id support can search for. */
  readonly incidentId: Signal<string | null>;

  constructor(
    private readonly errors: ErrorMessageService,
    form: AbstractControl,
    destroyRef: DestroyRef,
  ) {
    this.message = computed(() => this.failure()?.message ?? null);
    this.incidentId = computed(() => this.failure()?.incidentId ?? null);

    // Any edit clears the previous failure. Both halves matter: a banner that outlives the
    // problem it described is noise, and a field still marked as refused after the user has
    // corrected it is actively misleading.
    form.valueChanges.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => {
      if (this.failure() !== null) {
        this.failure.set(null);
      }
    });
  }

  begin(): void {
    this.failure.set(null);
    this.inFlight.set(true);
  }

  succeed(): void {
    this.inFlight.set(false);
    this.failure.set(null);
  }

  fail(error: unknown): void {
    this.inFlight.set(false);
    this.failure.set(this.errors.translate(error));
  }

  /** Whether the API named this field path in a validation failure. */
  rejected(field: string): boolean {
    return this.failure()?.fieldErrors.has(field) ?? false;
  }
}

/** `protected readonly state = submitState(this.form);` */
export function submitState(form: AbstractControl): SubmitState {
  return new SubmitState(inject(ErrorMessageService), form, inject(DestroyRef));
}
