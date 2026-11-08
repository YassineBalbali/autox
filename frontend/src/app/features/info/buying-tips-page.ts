import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Conseils pour acheter: general advice, and how the site's own tools help. */
@Component({
  selector: 'app-buying-tips-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Conseils pour acheter" lede="Ce qu’il faut regarder avant de s’engager.">
      <h2>Avant de contacter le vendeur</h2>
      <ul>
        <li>
          Regardez l’indicateur de prix. Un prix très en dessous du marché n’est pas toujours une affaire : demandez
          pourquoi.
        </li>
        <li>Comparez avec les véhicules similaires affichés sous l’annonce, et avec <a routerLink="/la-cote">La Cote</a>.</li>
        <li>
          Lisez la déclaration de dommages quand il y en a une : un véhicule endommagé est vendu en l’état, et
          l’annonce dit s’il roule.
        </li>
        <li>Pour une location (LOA ou LLD), regardez le coût total, pas seulement la mensualité.</li>
      </ul>

      <h2>Pendant la visite</h2>
      <ul>
        <li>Voyez le véhicule en personne, de jour, et essayez-le.</li>
        <li>
          Vérifiez la carte grise : le nom du vendeur, le numéro d’identification (VIN) gravé sur le véhicule, la date
          de première immatriculation.
        </li>
        <li>Demandez le contrôle technique de moins de six mois, obligatoire pour une voiture de plus de quatre ans.</li>
        <li>Demandez les factures d’entretien, et vérifiez la cohérence du kilométrage.</li>
        <li>Consultez l’historique du véhicule sur le service officiel HistoVec, avec le lien que le vendeur vous donne.</li>
      </ul>

      <h2>Pour payer</h2>
      <ul>
        <li>Ne versez jamais d’acompte avant d’avoir vu le véhicule.</li>
        <li>
          Méfiez-vous d’un vendeur à l’étranger, pressé, qui propose une livraison ou un paiement par un service qu’il
          choisit.
        </li>
        <li>Préférez un virement au moment de la remise des clés, ou un chèque de banque vérifié auprès de la banque.</li>
        <li>Repartez avec la carte grise barrée et signée, le certificat de cession et le code de cession.</li>
      </ul>

      <h2>Un doute ?</h2>
      <p>
        Gardez les échanges dans la messagerie du site, et signalez un message suspect avec le bouton prévu. Voir
        aussi le <a routerLink="/aide">centre d’aide</a>.
      </p>
    </app-info-page>
  `,
})
export class BuyingTipsPage {}
