import '../../core/auth/auth_state.dart';

/// The account's places in the app: the site's own paths.
abstract final class AccountLocations {
  static const account = '/compte';

  /// The neutral wait while the stored session is checked at launch.
  static const waiting = '/compte/attente';
  static const signIn = '/connexion';
  static const register = '/inscription';
  static const forgotPassword = '/mot-de-passe-oublie';

  /// The account just created: shown with "Vérifiez votre boîte mail".
  static const welcome = '/compte?bienvenue=1';
}

const _signedOutOnly = {AccountLocations.signIn, AccountLocations.register, AccountLocations.forgotPassword};

/// Where the router sends [location] for [state], or null to stay. Only the account's places are
/// steered: the search, the listings and the dealers are for everyone, whatever the session.
///
/// - Unknown (the stored session being checked): the account waits, rather than flashing the
///   sign-in form at someone who is signed in.
/// - Signed out: the account is the sign-in form.
/// - Signed in: the sign-in, registration and recovery forms are the account.
String? accountRedirect(AuthState state, Uri location) {
  final path = location.path;
  final account = path == AccountLocations.account || path == AccountLocations.waiting;
  switch (state) {
    case AuthUnknown():
      return path == AccountLocations.account ? AccountLocations.waiting : null;
    case SignedOut():
      return account ? AccountLocations.signIn : null;
    case SignedIn():
      if (_signedOutOnly.contains(path) || path == AccountLocations.waiting) {
        return AccountLocations.account;
      }
      return null;
    case SessionUnavailable():
      // The account says why and offers to try again; the forms stay reachable.
      return path == AccountLocations.waiting ? AccountLocations.account : null;
  }
}
