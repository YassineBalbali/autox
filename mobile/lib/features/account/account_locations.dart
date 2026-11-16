import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart' show BuildContext, MaterialPageRoute, Navigator;

import '../../core/auth/auth_state.dart';
import 'login_screen.dart';

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

  /// The parameter carrying where to go back to after signing in: the web's (`suivant`).
  static const returnParam = 'suivant';

  /// The sign-in form, coming back to [back] once signed in (or registered).
  static String signInFor(String back) => '$signIn?$returnParam=${Uri.encodeComponent(back)}';

  /// The registration form, with the same way back.
  static String registerFor(String? back) =>
      back == null ? register : '$register?$returnParam=${Uri.encodeComponent(back)}';
}

/// Signs in before going on: the sign-in form over the page that asks, as a page of its own (not a
/// route of the account's tab, which would take the tab bar with it), and true once signed in or
/// registered there. [back] is where to come back to, should the form need to go there itself.
Future<bool> signInFirst(BuildContext context, {required String back}) async =>
    await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => LoginScreen(returnTo: back))) ?? false;

/// Where [location] asks to come back to after signing in, when that is a place in the app: the
/// web's `safeReturnUrl` rule. A path only — one leading slash, not two, no backslash after it —
/// and not the sign-in form itself.
String? safeReturn(Uri location) {
  final value = location.queryParameters[AccountLocations.returnParam];
  if (value == null || value.isEmpty || !value.startsWith('/') || value.startsWith('//')) {
    return null;
  }
  if (value.startsWith(r'/\') || value == AccountLocations.signIn || value.startsWith('${AccountLocations.signIn}?')) {
    return null;
  }
  return value;
}

const _signedOutOnly = {AccountLocations.signIn, AccountLocations.register, AccountLocations.forgotPassword};

/// Signed in (or registered) from a form opened on the way to [returnTo]: back there — by popping
/// to the page that opened the form (with true: "signed in"), or by going there when the form was
/// reached by its address. False when the form was not on the way anywhere.
bool leaveForReturn(BuildContext context, String? returnTo) {
  if (returnTo == null) {
    return false;
  }
  final navigator = Navigator.of(context);
  if (navigator.canPop()) {
    navigator.pop(true);
  } else {
    context.go(returnTo);
  }
  return true;
}

/// Where the router sends [location] for [state], or null to stay. Only the account's places are
/// steered: the search, the listings and the dealers are for everyone, whatever the session.
///
/// - Unknown (the stored session being checked): the account waits, rather than flashing the
///   sign-in form at someone who is signed in.
/// - Signed out: the account is the sign-in form.
/// - Signed in: the sign-in, registration and recovery forms are the account — except on the way
///   back somewhere ([safeReturn]), which the form handles.
String? accountRedirect(AuthState state, Uri location) {
  final path = location.path;
  final account = path == AccountLocations.account || path == AccountLocations.waiting;
  switch (state) {
    case AuthUnknown():
      return path == AccountLocations.account ? AccountLocations.waiting : null;
    case SignedOut():
      if (account) {
        return AccountLocations.signIn;
      }
      // A conversation is its two participants' only: signed out, the tab says to sign in.
      return path.startsWith('/messages/') ? '/messages' : null;
    case SignedIn():
      // Signing in on the way somewhere (writing to a seller): the form itself goes back there,
      // by popping to the page that opened it, so that page keeps its place in the history.
      if (_signedOutOnly.contains(path) && safeReturn(location) != null) {
        return null;
      }
      if (_signedOutOnly.contains(path) || path == AccountLocations.waiting) {
        return AccountLocations.account;
      }
      return null;
    case SessionUnavailable():
      // The account says why and offers to try again; the forms stay reachable.
      return path == AccountLocations.waiting ? AccountLocations.account : null;
  }
}
