import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { AdminService, AdminStats, Split } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { formatDateTimeFr } from '../../shared/format/date';
import { NBSP, formatIntegerFr } from '../../shared/format/number';
import { Alert } from '../../shared/ui/alert';
import { DailyChart, DayCount } from './daily-chart';

type State = 'loading' | 'error' | 'ready';

/** "6,7 %", or a dash when there is nothing to take a share of. */
export function shareFr(part: number, whole: number): string {
  if (whole <= 0) {
    return '–';
  }
  return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format((100 * part) / whole)}${NBSP}%`;
}

/**
 * The landing page of the administration: what is happening on the site.
 *
 * <p>Every figure reads as real activity first, with the demo seed beside it in small. On the
 * public demo nearly every listing is seeded, so a single total would say how big the seed is
 * and nothing about whether anyone uses the site.
 *
 * <p>The API holds these figures for about a minute, and the page says when they were counted.
 */
@Component({
  selector: 'app-admin-dashboard-page',
  imports: [NgTemplateOutlet, RouterLink, Alert, DailyChart],
  templateUrl: './admin-dashboard-page.html',
  styleUrl: './admin-dashboard-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminDashboardPage {
  private readonly api = inject(AdminService);
  private readonly errors = inject(ErrorMessageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<State>('loading');
  protected readonly failure = signal<string | null>(null);
  protected readonly stats = signal<AdminStats | null>(null);
  protected readonly includeDemo = signal(false);

  protected readonly generatedAt = computed(() => formatDateTimeFr(this.stats()?.generatedAt));
  protected readonly showDemo = computed(() => this.stats()?.demoDataPresent === true);

  /** The queue holds every unreviewed listing, the seed's too, so its link counts them all. */
  protected readonly queueSize = computed(() => {
    const pending = this.stats()?.listings.pendingModeration;
    return pending ? pending.real + pending.demo : 0;
  });

  protected readonly listingsPerDay = computed<DayCount[]>(() =>
    (this.stats()?.daily ?? []).map((day) => ({ day: day.day, ...day.newListings })),
  );
  protected readonly registrationsPerDay = computed<DayCount[]>(() =>
    (this.stats()?.daily ?? []).map((day) => ({ day: day.day, ...day.registrations })),
  );

  protected readonly number = formatIntegerFr;
  protected readonly share = shareFr;

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.api
      .stats()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stats) => {
          this.stats.set(stats);
          this.state.set('ready');
        },
        error: (error: unknown) => {
          this.failure.set(this.errors.translate(error).message);
          this.state.set('error');
        },
      });
  }

  protected toggleDemo(event: Event): void {
    this.includeDemo.set((event.target as HTMLInputElement).checked);
  }

  /** "+ 152 démo", only where there is demo data to speak of. */
  protected demoNote(split: Split): string | null {
    return this.showDemo() && split.demo > 0 ? `+ ${formatIntegerFr(split.demo)} démo` : null;
  }
}
