import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Espace professionnel: what a dealer account is on AutoX, and nothing it is not. */
@Component({
  selector: 'app-pro-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Espace professionnel" lede="Pour les garages et les concessions.">
      <h2>Ce qu’un compte professionnel apporte</h2>
      <ul>
        <li><strong>Pas de limite d’annonces en ligne</strong> : un particulier en a 5.</li>
        <li>
          <strong>Votre entreprise sur vos annonces</strong> : raison sociale et informations que vous renseignez dans
          <a routerLink="/mon-compte/entreprise">Informations de l’entreprise</a>.
        </li>
        <li>
          <strong>Votre page dans l’<a routerLink="/professionnels">annuaire des professionnels</a></strong> : votre
          présentation, vos horaires, votre site, votre logo et toutes vos annonces, avec les filtres de la recherche.
          Les visiteurs vous écrivent depuis la page par la messagerie d’AutoX ; votre téléphone n’y figure que si vous le
          choisissez. Elle se règle dans <a routerLink="/mon-compte/entreprise">Informations de l’entreprise</a>.
        </li>
        <li><strong>Les offres de location</strong> : publiez une LOA ou une LLD avec sa mensualité et ses conditions.</li>
        <li>
          <strong>Le même outil pour tout gérer</strong> : vos annonces dans <a routerLink="/mes-annonces">Mes annonces</a>,
          vos échanges dans les <a routerLink="/messages">messages</a>.
        </li>
      </ul>

      <h2>Ouvrir un compte</h2>
      <p>
        <a routerLink="/inscription">Créez un compte</a> en choisissant « professionnel », avec la raison sociale et le
        numéro SIRET de l’entreprise, puis vérifiez votre adresse e-mail.
      </p>

      <h2>Ce qui n’existe pas</h2>
      <p>
        AutoX est un site de démonstration : pas d’abonnement, pas de mise en avant payante, pas d’import de stock ni de
        statistiques par annonce. Tout est gratuit.
      </p>
    </app-info-page>
  `,
})
export class ProPage {}
