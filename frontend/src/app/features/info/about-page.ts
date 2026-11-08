import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InfoPage } from './info-page';

/** Qui sommes-nous: one person and a portfolio project, said plainly. */
@Component({
  selector: 'app-about-page',
  imports: [InfoPage, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page heading="Qui sommes-nous" lede="Une personne, et un projet de portfolio.">
      <p>
        AutoX est le projet personnel de Yassine Balbali, développeur. Ce n’est pas une entreprise : c’est un site
        d’annonces de voitures d’occasion construit de bout en bout pour montrer ce que demande un vrai produit, du
        serveur à l’écran de téléphone.
      </p>
      <p>
        Tout y fonctionne pour de vrai : comptes, annonces et photos, recherche, messagerie, alertes par e-mail,
        modération. Mais les annonces publiées par AutoX lui-même sont inventées, et rien n’y est à vendre.
      </p>

      <h2>Ce qui compte ici</h2>
      <ul>
        <li>
          <strong>Des annonces lisibles</strong>, denses et comparables d’une ligne à l’autre, sur ordinateur comme sur
          téléphone.
        </li>
        <li>
          <strong>Le prix situé par rapport au marché</strong> : chaque annonce indique si elle est une bonne affaire,
          au prix du marché ou au-dessus, à partir des annonces comparables du site.
        </li>
        <li>
          <strong>Vos coordonnées protégées</strong> : on se contacte par la messagerie du site, sans jamais voir
          l’adresse e-mail de l’autre.
        </li>
        <li><strong>Pas de publicité, pas de suivi</strong> : voir la page <a routerLink="/cookies">cookies</a>.</li>
      </ul>

      <h2>En savoir plus</h2>
      <p>
        <a routerLink="/comment-ca-marche">Comment ça marche</a> ·
        <a routerLink="/mentions-legales">Mentions légales</a> ·
        <a routerLink="/confidentialite">Politique de confidentialité</a>
      </p>
    </app-info-page>
  `,
})
export class AboutPage {}
