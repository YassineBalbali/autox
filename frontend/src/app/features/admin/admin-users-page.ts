import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AdminService, AdminUserRow, UserRole, UserStatus } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { formatDateFr } from '../../shared/format/date';
import { formatIntegerFr } from '../../shared/format/number';
import { Alert } from '../../shared/ui/alert';
import { FILTERABLE_USER_STATUSES, USER_ROLES, USER_ROLE_LABELS, USER_STATUS_LABELS } from './user-labels';

export const USERS_PAGE_SIZE = 25;

type LoadState = 'loading' | 'ready' | 'error';

/** The value of `?comptes=`, as the API's yes or no. */
const DEMO_FILTER: Readonly<Record<string, boolean>> = { demo: true, reels: false };

/**
 * The accounts: find one, by a part of its name or address, its role, its status, or whether it
 * is one of the demo seed's.
 *
 * <p>The filters and the page number live in the URL, as on the moderation page, so a list can
 * be reloaded and shared, and Back returns to it from an account.
 */
@Component({
  selector: 'app-admin-users-page',
  imports: [RouterLink, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-users-page.html',
  styleUrl: './admin-users-page.scss',
})
export class AdminUsersPage {
  private readonly api = inject(AdminService);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);

  /** `?q=` — part of a name or an address. */
  readonly q = input<string | undefined>();
  readonly role = input<string | undefined>();
  readonly statut = input<string | undefined>();
  /** `?comptes=` — `demo` or `reels`. */
  readonly comptes = input<string | undefined>();
  /** `?page=` — one-based, as people count. */
  readonly page = input<string | undefined>();

  protected readonly roles = USER_ROLES;
  protected readonly statuses = FILTERABLE_USER_STATUSES;
  protected readonly roleLabels = USER_ROLE_LABELS;
  protected readonly statusLabels = USER_STATUS_LABELS;
  protected readonly date = formatDateFr;
  protected readonly number = formatIntegerFr;
  protected readonly suspended = UserStatus.SUSPENDED;

  /** A value the API does not know is no filter, rather than a 400 from a hand-edited URL. */
  protected readonly roleFilter = computed(() => this.known(USER_ROLES, this.role()));
  protected readonly statusFilter = computed(() => this.known(FILTERABLE_USER_STATUSES, this.statut()));
  protected readonly accountsFilter = computed(() => {
    const value = this.comptes();
    return value !== undefined && value in DEMO_FILTER ? value : '';
  });

  protected readonly pageIndex = computed(() => {
    const asked = Number(this.page());
    return Number.isInteger(asked) && asked >= 1 ? asked - 1 : 0;
  });

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadFailure = signal<string | null>(null);
  protected readonly items = signal<AdminUserRow[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);

  protected readonly filtered = computed(
    () => !!(this.q()?.trim() || this.roleFilter() || this.statusFilter() || this.accountsFilter()),
  );
  protected readonly countLine = computed(() => {
    const total = this.total();
    return `${formatIntegerFr(total)} compte${total > 1 ? 's' : ''}`;
  });
  protected readonly hasPrevious = computed(() => this.pageIndex() > 0);
  protected readonly hasNext = computed(() => this.pageIndex() + 1 < this.totalPages());

  /** Guards against an answer for filters the administrator has already changed. */
  private generation = 0;

  constructor() {
    effect(() => {
      this.q();
      this.roleFilter();
      this.statusFilter();
      this.accountsFilter();
      this.pageIndex();
      this.reload();
    });
  }

  protected reload(): void {
    this.state.set('loading');
    const generation = ++this.generation;
    const search = this.q()?.trim();
    const accounts = this.accountsFilter();
    this.api
      .userList({
        page: this.pageIndex(),
        size: USERS_PAGE_SIZE,
        q: search || undefined,
        role: (this.roleFilter() as UserRole) || undefined,
        status: (this.statusFilter() as UserStatus) || undefined,
        demo: accounts ? DEMO_FILTER[accounts] : undefined,
      })
      .subscribe({
        next: (answer) => {
          if (generation !== this.generation) {
            return;
          }
          this.items.set(answer.items ?? []);
          this.total.set(answer.totalElements ?? 0);
          this.totalPages.set(answer.totalPages ?? 1);
          this.loadFailure.set(null);
          this.state.set('ready');
        },
        error: (error: unknown) => {
          if (generation !== this.generation) {
            return;
          }
          this.loadFailure.set(this.errors.messageFor(error));
          this.state.set('error');
        },
      });
  }

  /** A changed filter starts again from the first page. */
  protected applyFilter(name: 'q' | 'role' | 'statut' | 'comptes', value: string): void {
    void this.router.navigate([], {
      queryParams: { [name]: value.trim() || null, page: null },
      queryParamsHandling: 'merge',
    });
  }

  private known(values: readonly string[], value: string | undefined): string {
    return value !== undefined && values.includes(value) ? value : '';
  }
}
