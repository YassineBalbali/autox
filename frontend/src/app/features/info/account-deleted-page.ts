import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Where deleting an account leads: what happened, and nothing to sign in to. */
@Component({
  selector: 'app-account-deleted-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Votre compte a été supprimé">
      <p>
        Votre profil, vos annonces et leurs photos, vos recherches enregistrées et vos sessions ont été effacés. Un
        e-mail de confirmation part vers votre adresse.
      </p>
      <p>
        Dans les conversations, vos messages apparaissent désormais sous « Compte supprimé », sans leur texte. Voir la
        <a routerLink="/confidentialite">politique de confidentialité</a>.
      </p>
      <p><a routerLink="/">Retour à l’accueil</a></p>
    </app-info-page>
  `,
})
export class AccountDeletedPage {}
