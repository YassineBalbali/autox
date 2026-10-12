import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';

/**
 * The application shell: the skip link, a minimal header, and the router outlet.
 *
 * <p>It reads `isAuthenticated` to choose between "Se connecter" and "Mon compte", which is
 * the one piece of state the chrome needs. A signal, so this renders correctly under
 * zoneless change detection when a sign-in or a sign-out changes it — there is no
 * subscription here and nothing to tear down.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly auth = inject(AuthService);

  protected readonly signedIn = this.auth.isAuthenticated;
}
