/// Words only the app says: the site has nothing to share them with. Everything the site also
/// shows — enum values, error codes, field names, the forms' messages — comes from
/// `frontend/labels.fr.json`, through `labels.g.dart`.
abstract final class AppLabels {
  /// After five seconds of waiting: the free server sleeps when idle and takes up to a minute to
  /// wake. A browser user sees the same wait, but the site has no place that words it.
  static const coldStart = 'Le serveur démarre, cela peut prendre jusqu’à une minute…';

  static const messagesSignedOut = 'Connectez-vous pour voir vos messages';

  static const sessionUnavailable =
      'Impossible de vérifier votre session pour le moment. Vos informations sont gardées sur ce téléphone.';

  static const acceptTermsBefore = 'J’accepte les ';
  static const acceptTermsLink = 'conditions générales';
  static const acceptTermsBetween = ' et la ';
  static const acceptTermsPrivacy = 'politique de confidentialité';
  static const acceptTermsRequired = 'Acceptez les conditions générales pour créer un compte.';

  static const myDataHint = 'Le téléchargement se fait sur le site, où il faut se connecter.';
}
