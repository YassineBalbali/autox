import 'package:autox/core/auth/session.dart';
import 'package:autox/features/account/account_locations.dart';
import 'package:autox_api/autox_api.dart' show UserSummary;
import 'package:flutter_test/flutter_test.dart';

/// The way back after signing in: the web's `safeReturnUrl` rule (auth.routes.spec.ts).
void main() {
  String? back(String value) => safeReturn(Uri.parse(AccountLocations.signInFor(value)));

  test('a path in the app is kept, its query included', () {
    expect(back('/annonces/l1?ecrire=1'), '/annonces/l1?ecrire=1');
    expect(back('/professionnels/garage-bellevue?ecrire=1'), '/professionnels/garage-bellevue?ecrire=1');
  });

  test('anything that could leave the app, or loop, is refused', () {
    for (final hostile in [
      'https://autox-connexion.example/',
      '//evil.example/x',
      r'/\evil.example',
      'annonces/l1',
      '/connexion',
      '/connexion?suivant=/annonces/l1',
      '',
    ]) {
      expect(back(hostile), isNull, reason: hostile);
    }
    expect(safeReturn(Uri.parse('/connexion')), isNull);
  });

  test('signed in on the way back, the form stays to leave by itself; otherwise it is the account', () {
    final signedIn = SignedIn(UserSummary(id: 'u1'));
    expect(accountRedirect(signedIn, Uri.parse(AccountLocations.signInFor('/annonces/l1'))), isNull);
    expect(accountRedirect(signedIn, Uri.parse('/connexion?suivant=https://evil.example')), AccountLocations.account);
  });

  test('signed out, a conversation is the Messages tab, which asks to sign in', () {
    expect(accountRedirect(const SignedOut(), Uri.parse('/messages/c1')), '/messages');
    expect(accountRedirect(const SignedOut(), Uri.parse('/messages')), isNull);
  });
}
