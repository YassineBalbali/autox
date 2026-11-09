import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ContactAddress, InfoPage } from './info-page';

/**
 * Politique de confidentialité, written from the code: the tables, the sweepers and their
 * retention constants (AuthMaintenanceSweeper, the throttles, the listing expiry), the providers
 * the server calls. A change to any of those is a change to this page.
 */
@Component({
  selector: 'app-privacy-page',
  imports: [InfoPage, ContactAddress, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page
      heading="Politique de confidentialité"
      lede="Ce qu’AutoX enregistre sur vous, pourquoi, combien de temps, et qui le traite."
      [legal]="true"
    >
      <h2>Qui est responsable</h2>
      <p>
        Le responsable du traitement est Yassine Balbali, éditeur d’AutoX, un projet personnel de portfolio
        sans activité commerciale (<a routerLink="/mentions-legales">mentions légales</a>). Pour toute question
        ou demande : <app-contact-address />.
      </p>
      <p>
        AutoX est un site de démonstration. N’y saisissez pas de données personnelles réelles dont vous
        n’avez pas besoin, ni un mot de passe utilisé ailleurs.
      </p>

      <h2>Ce que nous enregistrons</h2>
      <div class="info__table">
        <table>
          <thead>
            <tr><th scope="col">Données</th><th scope="col">Pourquoi</th><th scope="col">Combien de temps</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>
                <strong>Votre compte</strong> : adresse e-mail, mot de passe (chiffré, jamais en clair), nom
                affiché, et si vous les renseignez prénom, nom et téléphone ; pays, langue, type de compte, dates
                d’inscription, de vérification de l’adresse et de dernière connexion.
              </td>
              <td>Vous identifier et faire fonctionner votre compte.</td>
              <td>Jusqu’à ce que vous supprimiez le compte.</td>
            </tr>
            <tr>
              <td>
                <strong>Votre entreprise</strong> (comptes professionnels) : raison sociale, forme juridique,
                SIRET, TVA, site web, description, adresse, téléphone.
              </td>
              <td>Présenter le professionnel sur ses annonces.</td>
              <td>Tant que le compte existe.</td>
            </tr>
            <tr>
              <td>
                <strong>Vos annonces</strong> : le véhicule, son prix, sa description, le code postal et la ville
                où il se trouve, l’historique de ses prix, son nombre de vues.
              </td>
              <td>Les publier, les rechercher, les modérer.</td>
              <td>
                En ligne 60 jours, puis expirée. Une annonce que vous supprimez disparaît du site aussitôt, et elle est
                effacée 30 jours plus tard. Une annonce expirée ou vendue reste enregistrée, pour que vous la
                retrouviez dans vos annonces, jusqu’à ce que vous la supprimiez.
              </td>
            </tr>
            <tr>
              <td>
                <strong>Vos photos</strong>. Les métadonnées des fichiers (dont la position GPS de l’appareil)
                sont retirées à l’envoi.
              </td>
              <td>Illustrer vos annonces.</td>
              <td>
                Avec l’annonce : effacées avec elle, 30 jours après sa suppression. Un envoi jamais confirmé est effacé
                dans l’heure.
              </td>
            </tr>
            <tr>
              <td>
                <strong>Vos messages</strong> : le texte, l’expéditeur, la date, et quand chacun les a lus. Si un
                message est signalé : le motif, la note éventuelle et la décision.
              </td>
              <td>Permettre aux acheteurs et vendeurs de se parler ; traiter les signalements.</td>
              <td>
                Une conversation est effacée, avec ses messages, un an après son dernier message ; plus tard seulement
                si un de ses messages fait l’objet d’un signalement en cours.
              </td>
            </tr>
            <tr>
              <td>
                <strong>Vos signalements d’annonces</strong> : l’annonce, le motif, la précision éventuelle, et la
                décision du modérateur.
              </td>
              <td>Retirer les annonces frauduleuses ou trompeuses.</td>
              <td>Effacés avec l’annonce, ou avec votre compte.</td>
            </tr>
            <tr>
              <td>
                <strong>Vos recherches enregistrées</strong> : les critères, leur nom, et les annonces déjà
                envoyées par alerte.
              </td>
              <td>Vous envoyer chaque matin les nouvelles annonces qui y répondent.</td>
              <td>Jusqu’à ce que vous les supprimiez.</td>
            </tr>
            <tr>
              <td>
                <strong>Vos sessions</strong> : un libellé de l’appareil, le navigateur, l’adresse IP, les dates de
                connexion et d’utilisation.
              </td>
              <td>Vous garder connecté, et vous montrer les appareils connectés pour en déconnecter un.</td>
              <td>Une session vaut 90 jours ; elle est effacée 30 jours après son expiration.</td>
            </tr>
            <tr>
              <td>
                <strong>Les liens envoyés par e-mail</strong> : vérification d’adresse et réinitialisation du mot de
                passe (avec l’adresse IP de la demande). Seule une empreinte du lien est conservée.
              </td>
              <td>Vérifier votre adresse, vous laisser changer de mot de passe.</td>
              <td>Valables 24 heures (vérification) et 30 minutes (réinitialisation) ; effacés 30 jours après.</td>
            </tr>
            <tr>
              <td>
                <strong>Les e-mails envoyés</strong> : destinataire, objet, contenu, état de l’envoi.
              </td>
              <td>Les envoyer, réessayer en cas d’échec.</td>
              <td>90 jours après l’envoi.</td>
            </tr>
            <tr>
              <td>
                <strong>Les compteurs anti-abus</strong> : l’adresse IP ou le compte, et un nombre de tentatives
                (connexion, inscription, mot de passe oublié, messages, consultation, recherche intelligente).
              </td>
              <td>Limiter les abus et les attaques.</td>
              <td>Effacés 24 heures après la dernière tentative.</td>
            </tr>
            <tr>
              <td>
                <strong>Le journal de modération et d’administration</strong> : décisions sur les annonces,
                actions sur les comptes (avec l’adresse e-mail du compte et de l’administrateur), motif.
              </td>
              <td>Pouvoir justifier une décision et en retrouver l’auteur.</td>
              <td>
                Sans limite de durée. Quand un compte est supprimé, ce journal ne garde plus son adresse, ni le motif
                écrit à son sujet.
              </td>
            </tr>
            <tr>
              <td>
                <strong>Les journaux techniques</strong> du serveur, qui peuvent contenir une adresse IP.
              </td>
              <td>Diagnostiquer les pannes et les abus.</td>
              <td>Selon la durée de conservation de l’hébergeur (Render).</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        AutoX compte aussi, sans rien qui vous identifie, les filtres de recherche utilisés par heure (effacés
        après 200 jours) et le nombre de recherches intelligentes par jour.
      </p>

      <h2>Ce qui est public</h2>
      <p>
        Vos annonces, avec la ville et le code postal du véhicule, votre nom affiché et, pour un professionnel, les
        informations de l’entreprise. Votre adresse e-mail et votre téléphone personnel ne sont jamais affichés.
        Les administrateurs du site voient les comptes, les annonces et les messages signalés.
      </p>

      <h2>Sur quelle base</h2>
      <ul>
        <li>Le compte, les annonces, les messages et les alertes : le service que vous demandez en vous inscrivant.</li>
        <li>
          Les sessions, les compteurs anti-abus, les journaux et la modération : l’intérêt légitime de protéger le
          site et ses utilisateurs.
        </li>
      </ul>
      <p>AutoX ne fait ni publicité, ni profilage, ni revente de données.</p>

      <h2>Qui traite vos données</h2>
      <div class="info__table">
        <table>
          <thead>
            <tr><th scope="col">Prestataire</th><th scope="col">Rôle</th><th scope="col">Où</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>Render Services, Inc.</td>
              <td>Fait tourner le serveur d’AutoX ; conserve ses journaux techniques.</td>
              <td>Serveurs à Francfort (Allemagne) ; société américaine.</td>
            </tr>
            <tr>
              <td>Neon, LLC (Databricks)</td>
              <td>Héberge la base de données : tout ce qui est décrit plus haut.</td>
              <td>Serveurs à Francfort (Allemagne) ; société américaine.</td>
            </tr>
            <tr>
              <td>Supabase Pte. Ltd.</td>
              <td>Stocke les photos.</td>
              <td>Stockage en Irlande ; société singapourienne.</td>
            </tr>
            <tr>
              <td>Vercel Inc.</td>
              <td>Sert les pages du site ; voit l’adresse IP et le navigateur de chaque visiteur.</td>
              <td>Réseau mondial ; société américaine.</td>
            </tr>
            <tr>
              <td>Brevo (Sendinblue SAS)</td>
              <td>
                Envoie les e-mails : vérification, mot de passe, nouveaux messages, alertes. Brevo remplace les liens
                des e-mails par des liens de suivi, et enregistre donc les clics.
              </td>
              <td>France.</td>
            </tr>
            <tr>
              <td>Groq LLC</td>
              <td>
                Interprète le texte tapé dans la recherche intelligente (« SUV diesel de moins de 20 000 € »), quand
                elle est activée. Ce texte est envoyé à Groq ; AutoX ne le conserve pas. N’y écrivez rien de
                personnel.
              </td>
              <td>États-Unis.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        Plusieurs de ces sociétés sont établies hors de l’Union européenne. Les garanties qu’elles appliquent aux
        transferts de données sont décrites dans leurs propres conditions.
      </p>

      <h2>Vos droits</h2>
      <p>
        Vous pouvez demander l’accès à vos données, leur rectification, leur effacement, leur portabilité, la
        limitation de leur traitement, ou vous y opposer. Écrivez à <app-contact-address /> ; la réponse vient
        dans un délai d’un mois.
      </p>
      <p>L’essentiel se fait directement sur le site :</p>
      <ul>
        <li>
          télécharger une copie de vos données (compte, annonces, conversations, recherches, sessions), dans
          <a routerLink="/mon-compte/donnees">Mes données</a> ;
        </li>
        <li>supprimer votre compte, au même endroit, avec votre mot de passe ;</li>
        <li>modifier votre profil et les informations de votre entreprise, dans <a routerLink="/mon-compte">Mon compte</a> ;</li>
        <li>déconnecter un appareil, dans <a routerLink="/mon-compte/sessions">Appareils connectés</a> ;</li>
        <li>supprimer une annonce, dans <a routerLink="/mes-annonces">Mes annonces</a> ;</li>
        <li>
          supprimer une recherche enregistrée ou en suspendre les alertes, dans
          <a routerLink="/mes-recherches">Mes recherches</a>, ou par le lien de désabonnement de chaque alerte.
        </li>
      </ul>

      <h3>Quand vous supprimez votre compte</h3>
      <ul data-deletion>
        <li>
          Sont effacés : votre profil (adresse e-mail, nom, téléphone, entreprise), vos annonces et leurs photos, vos
          recherches enregistrées, vos sessions, vos signalements d’annonces.
        </li>
        <li>
          Vos conversations restent pour les personnes avec qui vous avez échangé : vos messages y apparaissent sous
          « Compte supprimé », sans leur texte. Un message qui fait l’objet d’un signalement en cours garde son texte
          jusqu’à la décision du modérateur, puis il est effacé.
        </li>
        <li>
          Les décisions de modération et le journal d’administration sont conservés, sans votre adresse ni votre nom ni
          les notes écrites à votre sujet.
        </li>
        <li>Un dernier e-mail confirme la suppression ; il est effacé 90 jours après l’envoi, comme les autres.</li>
      </ul>

      <h2>Réclamation</h2>
      <p>
        Si vous estimez que vos droits ne sont pas respectés, vous pouvez saisir la Commission nationale de
        l’informatique et des libertés (CNIL) :
        <a href="https://www.cnil.fr/fr/plaintes" rel="noopener">cnil.fr/fr/plaintes</a>, ou 3 place de Fontenoy,
        TSA 80715, 75334 Paris Cedex 07.
      </p>

      <h2>Votre navigateur</h2>
      <p>
        Ce que le site garde dans votre navigateur, et pourquoi : voir la page <a routerLink="/cookies">cookies</a>.
      </p>
    </app-info-page>
  `,
})
export class PrivacyPage {}
