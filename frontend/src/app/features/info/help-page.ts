import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ContactAddress, InfoPage } from './info-page';

/** Centre d'aide: the questions the site actually raises, answered from what it does. */
@Component({
  selector: 'app-help-page',
  imports: [InfoPage, ContactAddress, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Centre d’aide" lede="Les questions les plus fréquentes.">
      <h2>Les annonces sont-elles réelles ?</h2>
      <p>
        Non. AutoX est un site de démonstration : les annonces publiées par AutoX lui-même sont inventées, et leurs
        vendeurs ne répondent pas. Rien n’est à vendre.
      </p>

      <h2>Je n’ai pas reçu l’e-mail de vérification.</h2>
      <p>
        Regardez dans les indésirables. Sinon, demandez-en un nouveau avec « Renvoyer le lien », dans
        <a routerLink="/mon-compte">Mon compte</a>. Le lien est valable 24 heures.
      </p>

      <h2>J’ai oublié mon mot de passe.</h2>
      <p>
        Utilisez <a routerLink="/mot-de-passe-oublie">Mot de passe oublié</a>. Le lien reçu est valable 30 minutes ;
        changer de mot de passe déconnecte vos autres appareils.
      </p>

      <h2>Pourquoi ne puis-je pas publier ou écrire ?</h2>
      <p>
        Il faut d’abord vérifier votre adresse e-mail. Un particulier peut aussi avoir 5 annonces en ligne au plus ; et
        l’envoi de messages est limité pour freiner le démarchage. Le message affiché dit lequel s’applique.
      </p>

      <h2>Pourquoi mon annonce a-t-elle disparu ?</h2>
      <p>
        Une annonce expire au bout de 60 jours ; prolongez-la depuis <a routerLink="/mes-annonces">Mes annonces</a>.
        Elle a pu aussi être refusée ou retirée par la modération : le motif est indiqué sur l’annonce, dans Mes
        annonces.
      </p>

      <h2>Comment arrêter les alertes ?</h2>
      <p>
        Par le lien de désabonnement en bas de chaque alerte, ou dans <a routerLink="/mes-recherches">Mes recherches</a>,
        où vous pouvez aussi les suspendre ou les supprimer.
      </p>

      <h2>Un appareil que je ne reconnais pas est connecté.</h2>
      <p>
        Déconnectez-le dans <a routerLink="/mon-compte/sessions">Appareils connectés</a>, puis changez votre mot de passe.
      </p>

      <h2>Comment signaler un message ou une annonce ?</h2>
      <p>
        Un message se signale depuis la conversation, avec le bouton « Signaler » : arnaque, propos abusifs ou
        démarchage. Une annonce se signale avec « Signaler cette annonce », sous sa description ; voir
        <a routerLink="/signalement">Signaler une annonce</a>.
      </p>

      <h2>Comment supprimer mon compte ou obtenir mes données ?</h2>
      <p>
        Dans <a routerLink="/mon-compte/donnees">Mes données</a> : « Télécharger mes données » pour une copie, et
        « Supprimer mon compte », avec votre mot de passe. Voir la
        <a routerLink="/confidentialite">politique de confidentialité</a> pour ce qui est effacé et ce qui reste. Une
        question : <app-contact-address />.
      </p>
    </app-info-page>
  `,
})
export class HelpPage {}
