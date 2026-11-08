import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ContactAddress, InfoPage } from './info-page';

/** Mentions légales: who publishes the site and who hosts it. */
@Component({
  selector: 'app-legal-notice-page',
  imports: [InfoPage, ContactAddress, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Mentions légales" [legal]="true">
      <h2>Éditeur</h2>
      <p>
        AutoX est un projet personnel de portfolio, non commercial, édité par Yassine Balbali, personne
        physique. Il sert à montrer un travail de développement : ce n’est pas une entreprise, et rien
        n’y est vendu.
      </p>
      <dl>
        <dt>Directeur de la publication</dt>
        <dd>Yassine Balbali</dd>
        <dt>Contact</dt>
        <dd><app-contact-address /></dd>
      </dl>

      <h2>Un site de démonstration</h2>
      <p>
        Les annonces publiées par AutoX lui-même, leurs vendeurs, leurs prix et leurs offres de location sont
        fictifs. Aucun de ces véhicules n’existe ni n’est à vendre. Les annonces déposées par des visiteurs
        sont de leur seule responsabilité et peuvent être retirées à tout moment ; voir les
        <a routerLink="/conditions-generales">conditions générales d’utilisation</a>.
      </p>

      <h2>Hébergement</h2>
      <p>Le site est servi par quatre prestataires.</p>
      <dl>
        <dt>Le site web</dt>
        <dd>
          Vercel Inc., 440 N Barranca Avenue #4133, Covina, CA 91723, États-Unis —
          <a href="https://vercel.com" rel="noopener">vercel.com</a>
        </dd>
        <dt>Le serveur d’API</dt>
        <dd>
          Render Services, Inc., San Francisco, Californie, États-Unis
          (<mark class="info__placeholder" data-placeholder="render-address">[adresse postale à confirmer]</mark>) —
          <a href="https://render.com" rel="noopener">render.com</a>. Serveurs à Francfort (Allemagne).
        </dd>
        <dt>La base de données</dt>
        <dd>
          Neon, LLC, société du groupe Databricks, Inc., 160 Spear Street, San Francisco, CA 94105,
          États-Unis — <a href="https://neon.com" rel="noopener">neon.com</a>. Serveurs à Francfort (Allemagne).
        </dd>
        <dt>Les photos</dt>
        <dd>
          Supabase Pte. Ltd., 65 Chulia Street #38-02/03, OCBC Centre, Singapour 049513 —
          <a href="https://supabase.com" rel="noopener">supabase.com</a>. Stockage en Irlande.
        </dd>
      </dl>

      <h2>Propriété intellectuelle</h2>
      <p>
        Le code d’AutoX est l’œuvre de son éditeur. Les photos des annonces de démonstration sont des dessins
        générés par le site ; elles ne représentent aucun véhicule réel. Les marques et modèles de véhicules
        cités appartiennent à leurs détenteurs et ne sont mentionnés que pour décrire les annonces. Les
        icônes, les polices et la photo de la page d’accueil sont sous licence libre ; leurs licences sont
        reproduites dans le code source du projet.
      </p>

      <h2>Données personnelles</h2>
      <p>
        Ce que le site enregistre, pourquoi et combien de temps : voir la
        <a routerLink="/confidentialite">politique de confidentialité</a> et la page <a routerLink="/cookies">cookies</a>.
      </p>
    </app-info-page>
  `,
})
export class LegalNoticePage {}
