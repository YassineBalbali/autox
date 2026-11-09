import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Signaler une annonce: where the button is, what happens next. */
@Component({
  selector: 'app-report-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Signaler une annonce" lede="Une annonce vous paraît frauduleuse, trompeuse ou déplacée ? Dites-le aux modérateurs.">
      <h2>Où est le bouton</h2>
      <p>
        Sur la page de l’annonce, sous la description : <strong>« Signaler cette annonce »</strong>. Il faut être
        <a routerLink="/connexion">connecté</a>, avec une adresse e-mail vérifiée.
      </p>

      <h2>Ce que vous indiquez</h2>
      <ul>
        <li>un motif : arnaque, annonce trompeuse, contenu inapproprié, ou autre ;</li>
        <li>une précision si vous le souhaitez, obligatoire pour « autre ».</li>
      </ul>
      <p>
        Une annonce ne se signale qu’une fois par personne, et pas plus de cinq annonces par heure. Le vendeur ne sait
        pas qui l’a signalée.
      </p>

      <h2>Ce qui se passe ensuite</h2>
      <p>
        Un administrateur lit le signalement et décide : l’annonce reste en ligne, ou elle est retirée, avec un motif que
        le vendeur voit. Vous n’êtes pas prévenu de la décision.
      </p>

      <h2>Un message suspect ?</h2>
      <p>
        Il se signale dans la conversation, avec le bouton « Signaler » sous le message. Voir aussi les
        <a routerLink="/conseils-achat">conseils pour acheter</a>.
      </p>
    </app-info-page>
  `,
})
export class ReportPage {}
