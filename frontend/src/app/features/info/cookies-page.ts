import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/**
 * Cookies: exactly what the site keeps in the browser. The keys are the ones in the code
 * (token-store, favourites, recent-searches, view-preference, moderation-layout); a new one
 * belongs here, and the spec checks the list against them.
 */
@Component({
  selector: 'app-cookies-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Cookies" lede="AutoX ne dépose aucun cookie et ne vous suit pas." [legal]="true">
      <h2>Pas de cookie, pas de suivi</h2>
      <p>
        Le site ne dépose aucun cookie, ni le sien ni celui d’un tiers. Il ne contient ni mesure d’audience, ni
        publicité, ni bouton de réseau social, ni script venu d’un autre site : sa politique de sécurité l’interdit.
        Il n’y a donc rien à accepter ou à refuser, et pas de bandeau.
      </p>

      <h2>Ce qui est gardé dans votre navigateur</h2>
      <p>
        Quelques réglages sont gardés dans le stockage local de votre navigateur (<code>localStorage</code>). Ils y
        restent et ne sont envoyés nulle part, sauf la session, qui accompagne vos demandes au serveur d’AutoX.
      </p>
      <div class="info__table">
        <table>
          <thead>
            <tr><th scope="col">Clé</th><th scope="col">Ce qu’elle contient</th><th scope="col">Pourquoi</th></tr>
          </thead>
          <tbody>
            <tr data-key="autox.session">
              <td><code>autox.session</code></td>
              <td>Vos jetons de connexion, si vous êtes connecté.</td>
              <td>Rester connecté d’une page à l’autre. Effacée quand vous vous déconnectez.</td>
            </tr>
            <tr data-key="autox.favourites">
              <td><code>autox.favourites</code></td>
              <td>Les annonces que vous avez mises en favori.</td>
              <td>Les retrouver. Les favoris ne sont gardés que dans ce navigateur.</td>
            </tr>
            <tr data-key="autox.search.recent">
              <td><code>autox.search.recent</code></td>
              <td>Vos dernières recherches.</td>
              <td>Vous les reproposer sur la page d’accueil.</td>
            </tr>
            <tr data-key="autox.search.view">
              <td><code>autox.search.view</code></td>
              <td>L’affichage choisi pour les résultats : liste ou grille.</td>
              <td>Le garder d’une visite à l’autre.</td>
            </tr>
            <tr data-key="autox.admin.moderation.layout">
              <td><code>autox.admin.moderation.layout</code></td>
              <td>L’affichage choisi dans la modération : cartes ou liste.</td>
              <td>Le garder d’une visite à l’autre (administrateurs seulement).</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        Pour tout effacer, videz les données du site dans les réglages de votre navigateur. Vous serez déconnecté et
        perdrez vos favoris et vos dernières recherches ; rien d’autre ne change.
      </p>

      <h2>Les photos</h2>
      <p>
        Les photos des annonces sont chargées depuis le service de stockage d’AutoX (Supabase), par des liens
        temporaires. Ce chargement ne dépose pas de cookie. Comme pour toute page web, le service voit l’adresse IP du
        navigateur qui les demande ; voir la <a routerLink="/confidentialite">politique de confidentialité</a>.
      </p>
    </app-info-page>
  `,
})
export class CookiesPage {}
