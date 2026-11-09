import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ContactAddress, InfoPage } from './info-page';

/** Conditions générales d'utilisation, as the site actually works: post-moderation, the caps, the reasons. */
@Component({
  selector: 'app-terms-page',
  imports: [InfoPage, ContactAddress, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Conditions générales d’utilisation" [legal]="true">
      <h2>1. Un site de démonstration</h2>
      <p>
        AutoX est un projet personnel de portfolio, non commercial, édité par Yassine Balbali. Il imite un site
        d’annonces de voitures d’occasion pour montrer un travail de développement.
      </p>
      <ul>
        <li>
          <strong>Rien n’y est à vendre.</strong> Les annonces publiées par AutoX lui-même, leurs vendeurs, leurs
          prix et leurs offres de location sont inventés. Leurs vendeurs ne répondent pas aux messages.
        </li>
        <li>
          AutoX ne vend rien, ne prend aucune commission et n’intervient dans aucune transaction. Aucun paiement ne
          passe par le site.
        </li>
        <li>
          Le site peut être interrompu, modifié ou remis à zéro à tout moment, sans préavis, et les comptes, annonces
          et messages supprimés avec lui.
        </li>
      </ul>
      <p>En créant un compte ou en publiant une annonce, vous acceptez ces conditions.</p>

      <h2>2. Votre compte</h2>
      <ul>
        <li>Un compte par personne. Il est réservé aux personnes majeures.</li>
        <li>Votre adresse e-mail doit être vérifiée avant que vous puissiez publier ou écrire.</li>
        <li>
          Un particulier peut avoir 5 annonces en ligne à la fois. Un professionnel déclare son entreprise et n’a pas
          de limite.
        </li>
        <li>
          Vous êtes responsable de ce qui est publié depuis votre compte. N’utilisez pas un mot de passe qui sert
          ailleurs.
        </li>
      </ul>

      <h2>3. Ce que vous pouvez publier</h2>
      <p>
        Des annonces de véhicules, décrits honnêtement : l’état réel, les dommages déclarés comme tels, un prix ou une
        offre de location que vous seriez prêt à tenir, des photos du véhicule.
      </p>

      <h2>4. Ce que vous ne pouvez pas publier</h2>
      <ul>
        <li>une annonce trompeuse, ou pour un véhicule qui n’existe pas ou ne vous appartient pas ;</li>
        <li>une tentative d’escroquerie, d’hameçonnage ou de paiement hors de tout cadre ;</li>
        <li>la même annonce plusieurs fois ;</li>
        <li>autre chose qu’un véhicule ;</li>
        <li>des photos inappropriées, ou qui ne sont pas les vôtres ;</li>
        <li>un contenu illégal, haineux, injurieux ou portant atteinte aux droits d’autrui ;</li>
        <li>vos coordonnées dans le texte de l’annonce : la messagerie du site est là pour ça ;</li>
        <li>dans les messages : du démarchage en masse, des propos insultants ou une arnaque.</li>
      </ul>

      <h2>5. Modération</h2>
      <p>
        Une annonce est en ligne dès sa publication, puis relue par un administrateur. Une annonce contraire à ces
        conditions est refusée ou retirée, avec un motif : suspicion de fraude, informations trompeuses, doublon,
        pas un véhicule, photos inappropriées, contenu interdit, coordonnées dans le texte, ou autre. Une annonce
        modifiée après avoir été acceptée est relue de nouveau.
      </p>
      <p>
        Chacun peut signaler une annonce (arnaque, annonce trompeuse, contenu inapproprié) avec le bouton « Signaler
        cette annonce », et un message reçu (arnaque, propos abusifs, démarchage). Un administrateur décide : il retire
        l’annonce ou masque le message, ou classe le signalement. Un compte qui enfreint ces conditions peut être
        suspendu.
      </p>
      <p>Pour contester une décision : <app-contact-address />.</p>

      <h2>6. Suppression du compte</h2>
      <p>
        Vous pouvez supprimer votre compte à tout moment, dans <a routerLink="/mon-compte/donnees">Mes données</a>. Ce qui
        est effacé et ce qui reste est décrit dans la <a routerLink="/confidentialite">politique de confidentialité</a>.
      </p>

      <h2>7. Responsabilité</h2>
      <p>
        AutoX est fourni tel quel, gratuitement et à titre de démonstration, sans garantie de disponibilité ni
        d’exactitude. L’éditeur ne vérifie pas les véhicules, les vendeurs ni les acheteurs, et n’est pas partie aux
        échanges entre utilisateurs. La valeur affichée par l’indicateur de prix et par La Cote est une estimation
        calculée sur les annonces du site, pas une expertise. Ne versez jamais d’argent à quelqu’un rencontré ici
        sans avoir vu le véhicule et ses papiers.
      </p>

      <h2>8. Vos données</h2>
      <p>
        Voir la <a routerLink="/confidentialite">politique de confidentialité</a> et la page
        <a routerLink="/cookies">cookies</a>.
      </p>

      <h2>9. Droit applicable</h2>
      <p>Ces conditions sont soumises au droit français. Elles peuvent changer ; la date en haut de page l’indique.</p>
    </app-info-page>
  `,
})
export class TermsPage {}
