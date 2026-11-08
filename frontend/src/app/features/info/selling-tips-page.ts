import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Conseils pour vendre: a good listing, as the editor and moderation see one. */
@Component({
  selector: 'app-selling-tips-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Conseils pour vendre" lede="Une annonce complète se vend plus vite, et sans mauvaise surprise.">
      <h2>Le prix</h2>
      <ul>
        <li>Estimez votre voiture avec <a routerLink="/la-cote">La Cote</a>, qui la compare aux annonces en ligne.</li>
        <li>
          Une fois l’annonce publiée, l’indicateur de prix dit aux acheteurs où vous vous situez. Vous pouvez baisser le
          prix à tout moment ; l’historique des prix est conservé.
        </li>
      </ul>

      <h2>Les photos</h2>
      <ul>
        <li>Jusqu’à 20 photos, en JPEG ou PNG, de 400 pixels au moins de côté et de 10 Mo au plus.</li>
        <li>De jour, voiture propre : les quatre côtés, l’intérieur, le tableau de bord allumé, le compteur, les pneus.</li>
        <li>Montrez les défauts : un acheteur qui les découvre à la visite repart.</li>
        <li>Les métadonnées des photos, dont la position GPS, sont retirées à l’envoi.</li>
      </ul>

      <h2>La description</h2>
      <ul>
        <li>
          Remplissez les caractéristiques : version, puissance, équipements, nombre de propriétaires, entretien. Ce sont
          elles que les filtres de recherche lisent.
        </li>
        <li>Dites l’état réel. Un véhicule accidenté ou en panne se déclare comme tel dans l’annonce.</li>
        <li>
          Ne mettez ni téléphone ni e-mail dans le texte : l’annonce serait refusée. Les acheteurs vous écrivent par la
          messagerie, sans voir votre adresse.
        </li>
      </ul>

      <h2>Après la publication</h2>
      <ul>
        <li>L’annonce est en ligne aussitôt, pour 60 jours, puis relue par un administrateur.</li>
        <li>
          Une modification du texte, du véhicule ou des photos la fait relire ; un changement de prix, non.
        </li>
        <li>
          Vendu ? Marquez-la vendue dans <a routerLink="/mes-annonces">Mes annonces</a> : elle disparaît des résultats.
        </li>
      </ul>

      <h2>La vente</h2>
      <p>
        Remettez la carte grise barrée avec la mention « vendu le … », signée, un certificat de cession (cerfa 15776),
        un contrôle technique de moins de six mois si la voiture a plus de quatre ans, et déclarez la cession en ligne
        sur le site de l’ANTS.
      </p>
    </app-info-page>
  `,
})
export class SellingTipsPage {}
