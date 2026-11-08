import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Comment ça marche: the site's real features, for a buyer and for a seller. */
@Component({
  selector: 'app-how-it-works-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Comment ça marche" lede="Chercher, comparer, contacter ; ou publier, répondre, vendre.">
      <h2>Pour acheter</h2>
      <ol>
        <li>
          <strong>Cherchez</strong> depuis l’<a routerLink="/">accueil</a> ou la <a routerLink="/recherche">recherche</a> :
          marque, modèle, prix, année, kilométrage, énergie, boîte, carrosserie, lieu, et plus. Vous pouvez aussi
          décrire ce que vous cherchez en une phrase ; la recherche intelligente la traduit en filtres, que vous
          pouvez ensuite corriger.
        </li>
        <li>
          <strong>Comparez.</strong> Chaque annonce indique « Bonne affaire », « Prix du marché » ou « Au-dessus du
          marché » quand le site a assez d’annonces comparables pour en juger. Une annonce montre aussi des véhicules
          similaires.
        </li>
        <li>
          <strong>Gardez</strong> une annonce en favori, ou enregistrez votre recherche pour recevoir chaque matin par
          e-mail les nouvelles annonces qui y répondent.
        </li>
        <li>
          <strong>Contactez le vendeur</strong> par la messagerie du site, après avoir créé un compte et vérifié votre
          adresse e-mail. Vous êtes prévenu par e-mail de sa réponse.
        </li>
      </ol>

      <h2>Pour vendre</h2>
      <ol>
        <li><strong>Créez un compte</strong>, particulier ou professionnel, et vérifiez votre adresse e-mail.</li>
        <li>
          <strong>Déposez l’annonce</strong> : le véhicule, son état, ses équipements, ses photos, son prix. Vous
          pouvez aussi proposer une location (LOA ou LLD), ou déclarer un véhicule endommagé.
        </li>
        <li>
          <strong>Elle est en ligne aussitôt</strong>, pour 60 jours, et relue ensuite par un administrateur.
          Vous la retrouvez dans <a routerLink="/mes-annonces">Mes annonces</a> pour la modifier, la prolonger, la
          marquer vendue ou la retirer.
        </li>
        <li><strong>Répondez</strong> aux acheteurs depuis vos <a routerLink="/messages">messages</a>.</li>
      </ol>
      <p>Pour estimer votre voiture avant de fixer un prix, utilisez <a routerLink="/la-cote">La Cote</a>.</p>

      <h2>Ce qui est gratuit</h2>
      <p>Tout. AutoX ne vend rien et ne prend aucune commission : c’est un site de démonstration.</p>
    </app-info-page>
  `,
})
export class HowItWorksPage {}
