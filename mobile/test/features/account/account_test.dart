import 'dart:async';

import 'package:autox/core/api/api_failure.dart';
import 'package:autox/core/auth/session.dart';
import 'package:autox/core/auth/token_store.dart';
import 'package:autox/core/labels/app_labels.dart';
import 'package:autox/features/account/account_locations.dart';
import 'package:autox/features/account/data_export.dart';
import 'package:autox_api/autox_api.dart' show UserSummary;
import 'package:dio/dio.dart';
import 'package:flutter/gestures.dart' show TapGestureRecognizer;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_auth.dart';
import '../../support/fake_http.dart';
import '../../support/pump_app.dart';

Finder _field(String label) => find.widgetWithText(TextField, label);
Finder _button(String label) => find.widgetWithText(FilledButton, label);

void main() {
  late FakeAccountApi api;
  late FakeHttp http;

  setUp(() {
    api = FakeAccountApi();
    http = api.http();
  });

  group('the redirect', () {
    Uri at(String location) => Uri.parse(location);

    test('the account waits while the session is unknown, never showing the sign-in form', () {
      expect(accountRedirect(const AuthUnknown(), at('/compte')), AccountLocations.waiting);
      expect(accountRedirect(const AuthUnknown(), at('/recherche')), isNull);
    });

    test('signed out, the account is the sign-in form; the forms stay', () {
      expect(accountRedirect(const SignedOut(), at('/compte')), AccountLocations.signIn);
      expect(accountRedirect(const SignedOut(), at('/compte/attente')), AccountLocations.signIn);
      expect(accountRedirect(const SignedOut(), at('/inscription')), isNull);
    });

    test('signed in, the forms are the account', () {
      final signedIn = SignedIn(UserSummary(id: 'u1'));
      for (final form in ['/connexion', '/inscription', '/mot-de-passe-oublie', '/compte/attente']) {
        expect(accountRedirect(signedIn, at(form)), AccountLocations.account, reason: form);
      }
      expect(accountRedirect(signedIn, at('/compte?bienvenue=1')), isNull);
      expect(accountRedirect(signedIn, at('/annonces/l1')), isNull);
    });
  });

  group('sign-in', () {
    testWidgets('every field and button is labelled for TalkBack', (tester) async {
      final semantics = tester.ensureSemantics();
      await pumpApp(tester, http, location: '/connexion');
      await tester.pumpAndSettle();

      expect(find.bySemanticsLabel('Adresse e-mail'), findsOneWidget);
      expect(find.bySemanticsLabel('Mot de passe'), findsOneWidget);
      expect(find.byTooltip('Afficher le mot de passe'), findsOneWidget);
      expect(find.bySemanticsLabel('Se connecter'), findsWidgets);
      expect(find.bySemanticsLabel('Mot de passe oublié ?'), findsOneWidget);
      expect(find.bySemanticsLabel('Créer un compte'), findsOneWidget);
      semantics.dispose();
    });

    testWidgets('empty fields say what is missing, in the site’s words, and nothing is sent', (tester) async {
      await pumpApp(tester, http, location: '/connexion');
      await tester.pumpAndSettle();

      await tester.tap(_button('Se connecter'));
      await tester.pump();

      expect(find.text('Ce champ est obligatoire.'), findsNWidgets(2));
      await tester.enterText(_field('Adresse e-mail'), 'pas-une-adresse');
      await tester.tap(_button('Se connecter'));
      await tester.pump();
      expect(find.text('Saisissez une adresse e-mail valide, par exemple nom@exemple.fr.'), findsOneWidget);
      expect(api.bodies['/api/v1/auth/login'], isNull);
    });

    testWidgets('the password can be shown and hidden', (tester) async {
      await pumpApp(tester, http, location: '/connexion');
      await tester.pumpAndSettle();
      TextField password() => tester.widget<TextField>(_field('Mot de passe'));

      expect(password().obscureText, isTrue);
      await tester.tap(find.byTooltip('Afficher le mot de passe'));
      await tester.pump();
      expect(password().obscureText, isFalse);
      expect(find.byTooltip('Masquer le mot de passe'), findsOneWidget);
    });

    testWidgets('wrong credentials, and too many attempts with the wait', (tester) async {
      await pumpApp(tester, http, location: '/connexion');
      await tester.pumpAndSettle();
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.enterText(_field('Mot de passe'), 'pas le bon');

      api.answers['/api/v1/auth/login'] = const FakeResponse.json(401, {'status': 401, 'code': 'INVALID_CREDENTIALS'});
      await tester.tap(_button('Se connecter'));
      await tester.pumpAndSettle();
      expect(find.text('Adresse e-mail ou mot de passe incorrect.'), findsOneWidget);

      api.answers['/api/v1/auth/login'] = const FakeResponse.json(429, {
        'status': 429,
        'code': 'TOO_MANY_ATTEMPTS',
        'retryAfterSeconds': 120,
      });
      await tester.tap(_button('Se connecter'));
      await tester.pumpAndSettle();
      expect(find.text('Trop de tentatives de connexion. Réessayez dans 2 minutes.'), findsOneWidget);
    });

    testWidgets('throttled by address (RATE_LIMITED), and refused accounts (403), each in its own words', (
      tester,
    ) async {
      final tokens = MemoryTokenStore();
      await pumpApp(tester, http, location: '/connexion', tokens: tokens);
      await tester.pumpAndSettle();
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.enterText(_field('Mot de passe'), 'motdepasselong');

      final cases = {
        const FakeResponse.json(429, {'status': 429, 'code': 'RATE_LIMITED', 'retryAfterSeconds': 45}):
            'Trop de requêtes. Réessayez dans 45 secondes.',
        const FakeResponse.json(403, {'status': 403, 'code': 'ACCOUNT_SUSPENDED'}):
            'Ce compte est suspendu. Contactez le support.',
        const FakeResponse.json(403, {'status': 403, 'code': 'ACCOUNT_CLOSED'}): 'Ce compte a été fermé.',
      };
      for (final MapEntry(key: answer, value: sentence) in cases.entries) {
        api.answers['/api/v1/auth/login'] = answer;
        await tester.tap(_button('Se connecter'));
        await tester.pumpAndSettle();
        expect(find.text(sentence), findsOneWidget);
      }
      expect(tokens.value, isNull);
      expect(_button('Se connecter'), findsOneWidget);
    });

    testWidgets('while signing in: the button is disabled, then the cold-start hint after 5 s, not before', (
      tester,
    ) async {
      await pumpApp(tester, http, location: '/connexion');
      await tester.pumpAndSettle();
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.enterText(_field('Mot de passe'), 'motdepasselong');
      api.gates['/api/v1/auth/login'] = Completer<void>();

      await tester.tap(_button('Se connecter'));
      await tester.pump();
      expect(tester.widget<FilledButton>(_button('Connexion…')).onPressed, isNull);
      await tester.tap(_button('Connexion…'));
      await tester.pump(const Duration(seconds: 4));
      expect(find.text(AppLabels.coldStart), findsNothing);
      await tester.pump(const Duration(seconds: 1, milliseconds: 100));
      expect(find.text(AppLabels.coldStart), findsOneWidget);

      api.gates['/api/v1/auth/login']!.complete();
      await tester.pumpAndSettle();
      expect(api.bodies['/api/v1/auth/login'], hasLength(1), reason: 'one sign-in, however many taps');
      expect(find.text('Jean Dupont'), findsOneWidget);
      expect(find.text(AppLabels.coldStart), findsNothing);
    });

    testWidgets('signing in sends the device label and opens the account', (tester) async {
      await pumpApp(tester, http, location: '/connexion');
      await tester.pumpAndSettle();
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.enterText(_field('Mot de passe'), 'motdepasselong');
      await tester.tap(_button('Se connecter'));
      await tester.pumpAndSettle();

      expect(api.bodies['/api/v1/auth/login']!.single, {
        'email': 'jean@exemple.fr',
        'password': 'motdepasselong',
        'deviceLabel': 'Application Android',
      });
      expect(find.text('Mon compte'), findsOneWidget);
      expect(find.text('jean@exemple.fr'), findsOneWidget);
      expect(find.text('Compte particulier'), findsOneWidget);
    });
  });

  group('registration', () {
    Future<void> fill(WidgetTester tester, {String confirmation = 'unmotdepasselong'}) async {
      await tester.enterText(_field('Nom affiché'), 'Jean D.');
      await tester.enterText(_field('Adresse e-mail'), 'nouveau@exemple.fr');
      await tester.enterText(_field('Mot de passe'), 'unmotdepasselong');
      await tester.enterText(_field('Confirmer le mot de passe'), confirmation);
    }

    testWidgets('the site’s rules', (tester) async {
      useTallScreen(tester);
      await pumpApp(tester, http, location: '/inscription');
      await tester.pumpAndSettle();

      await tester.enterText(_field('Mot de passe'), 'court');
      await tester.enterText(_field('Téléphone'), '0612345678');
      await tester.tap(_button('Créer mon compte'));
      await tester.pump();

      expect(find.text('Ce champ est obligatoire.'), findsWidgets);
      expect(find.text('Au moins 12 caractères.'), findsOneWidget);
      expect(find.text('Utilisez le format international, par exemple +33612345678.'), findsOneWidget);
      expect(api.bodies['/api/v1/auth/register'], isNull);

      await fill(tester, confirmation: 'autrechose12345');
      await tester.tap(_button('Créer mon compte'));
      await tester.pump();
      expect(find.text('Les deux mots de passe ne correspondent pas.'), findsOneWidget);
    });

    testWidgets('under the button, the web’s terms notice; its two links open the site’s pages', (tester) async {
      useTallScreen(tester);
      final opened = <Uri>[];
      await pumpApp(tester, http, location: '/inscription', opened: opened);
      await tester.pumpAndSettle();

      const sentence = 'En créant un compte, vous acceptez les CGU et la politique de confidentialité.';
      final notice = find.text(sentence, findRichText: true);
      expect(notice, findsOneWidget);
      expect(find.byType(Checkbox), findsNothing);
      expect(tester.getTopLeft(notice).dy, greaterThan(tester.getBottomLeft(_button('Créer mon compte')).dy));

      final spans = <InlineSpan>[];
      tester.widget<RichText>(notice).text.visitChildren((span) {
        spans.add(span);
        return true;
      });
      for (final text in ['CGU', 'politique de confidentialité']) {
        final link = spans.whereType<TextSpan>().singleWhere((span) => span.text == text);
        (link.recognizer! as TapGestureRecognizer).onTap!();
      }

      expect(opened, [
        Uri.parse('http://api.test-site/conditions-generales'),
        Uri.parse('http://api.test-site/confidentialite'),
      ]);
    });

    testWidgets('a new private account is created, signed in, and told to check its mailbox', (tester) async {
      useTallScreen(tester);
      api.emailVerified = false;
      final tokens = MemoryTokenStore();
      await pumpApp(tester, http, location: '/inscription', tokens: tokens);
      await tester.pumpAndSettle();

      await fill(tester);
      await tester.tap(_button('Créer mon compte'));
      await tester.pumpAndSettle();

      expect(api.bodies['/api/v1/auth/register']!.single, {
        'email': 'nouveau@exemple.fr',
        'password': 'unmotdepasselong',
        'role': 'PARTICULIER',
        'displayName': 'Jean D.',
        'countryCode': 'FR',
      });
      expect(find.text('Vérifiez votre boîte mail'), findsOneWidget);
      expect(find.textContaining('Un lien de vérification a été envoyé à'), findsOneWidget);
      expect(tokens.value, 'refresh-1');
    });

    testWidgets('an address already registered says so', (tester) async {
      useTallScreen(tester);
      api.answers['/api/v1/auth/register'] = const FakeResponse.json(409, {
        'status': 409,
        'code': 'EMAIL_ALREADY_REGISTERED',
      });
      await pumpApp(tester, http, location: '/inscription');
      await tester.pumpAndSettle();
      await fill(tester);
      await tester.tap(_button('Créer mon compte'));
      await tester.pumpAndSettle();

      expect(find.text('Un compte existe déjà avec cette adresse e-mail.'), findsOneWidget);
    });
  });

  group('forgotten password', () {
    testWidgets('the link is requested, and the answer says nothing about whether the account exists', (tester) async {
      await pumpApp(tester, http, location: '/mot-de-passe-oublie');
      await tester.pumpAndSettle();
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.tap(_button('Envoyer le lien'));
      await tester.pumpAndSettle();

      expect(api.bodies['/api/v1/auth/password/forgot']!.single, {'email': 'jean@exemple.fr'});
      expect(find.textContaining('Si un compte AutoX existe avec cette adresse'), findsOneWidget);
      expect(_button('Envoyer le lien'), findsNothing);
    });

    testWidgets('too many requests, with the wait', (tester) async {
      api.answers['/api/v1/auth/password/forgot'] = const FakeResponse.json(429, {
        'status': 429,
        'code': 'RATE_LIMITED',
        'retryAfterSeconds': 30,
      });
      await pumpApp(tester, http, location: '/mot-de-passe-oublie');
      await tester.pumpAndSettle();
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.tap(_button('Envoyer le lien'));
      await tester.pumpAndSettle();

      expect(find.text('Trop de requêtes. Réessayez dans 30 secondes.'), findsOneWidget);
    });
  });

  group('the signed-in account', () {
    testWidgets('at launch, a stored session shows a neutral wait, never the sign-in form, then the account', (
      tester,
    ) async {
      api.gates['/api/v1/auth/refresh'] = Completer<void>();
      await pumpApp(tester, http, location: '/compte', tokens: MemoryTokenStore('refresh-stored'));
      await tester.pump();
      await tester.pump();

      expect(find.bySemanticsLabel('Vérification de la session'), findsOneWidget);
      expect(_button('Se connecter'), findsNothing);

      api.gates['/api/v1/auth/refresh']!.complete();
      await tester.pumpAndSettle();
      expect(find.text('Jean Dupont'), findsOneWidget);
      expect(api.bodies['/api/v1/auth/refresh']!.single, {'refreshToken': 'refresh-stored'});
    });

    testWidgets('an unverified address can ask for a new link', (tester) async {
      api.emailVerified = false;
      await pumpApp(tester, http, location: '/compte', tokens: MemoryTokenStore('refresh-stored'));
      await tester.pumpAndSettle();

      expect(find.textContaining('E-mail non vérifié.'), findsOneWidget);
      await tester.tap(find.widgetWithText(OutlinedButton, 'Renvoyer le lien'));
      await tester.pumpAndSettle();

      expect(api.bodies['/api/v1/me/email/verification'], hasLength(1));
      expect(
        find.text('Nouveau lien envoyé à jean@exemple.fr. Il arrive parfois dans les indésirables.'),
        findsOneWidget,
      );
    });

    testWidgets('"Mes données": disabled while preparing, the cold-start hint after 5 s, then the failure in words', (
      tester,
    ) async {
      // What is written and shared is data_export_test's; here, the screen around it.
      final release = Completer<void>();
      var calls = 0;
      await pumpApp(
        tester,
        http,
        location: '/compte',
        tokens: MemoryTokenStore('refresh-stored'),
        overrides: [
          dataExportProvider.overrideWithValue(() async {
            calls++;
            await release.future;
            throw const OfflineFailure();
          }),
        ],
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Mes données'));
      await tester.pump();
      expect(find.text('Préparation…'), findsOneWidget);
      expect(tester.widget<ListTile>(find.widgetWithText(ListTile, 'Préparation…')).enabled, isFalse);
      await tester.tap(find.text('Préparation…'));
      expect(calls, 1);

      await tester.pump(const Duration(seconds: 4));
      expect(find.text(AppLabels.coldStart), findsNothing);
      await tester.pump(const Duration(seconds: 2));
      expect(find.text(AppLabels.coldStart), findsOneWidget);

      release.complete();
      await tester.pumpAndSettle();
      expect(find.text('Impossible de joindre AutoX. Vérifiez votre connexion internet.'), findsOneWidget);
      expect(find.text('Mes données'), findsOneWidget);
      expect(find.text(AppLabels.coldStart), findsNothing);
    });

    testWidgets('signing out returns to the sign-in form, even when the server cannot be reached', (tester) async {
      final tokens = MemoryTokenStore('refresh-stored');
      await pumpApp(tester, http, location: '/compte', tokens: tokens);
      await tester.pumpAndSettle();
      api.answers['/api/v1/auth/logout'] = const FakeResponse.error(DioExceptionType.connectionError);

      await tester.tap(find.text('Se déconnecter'));
      await tester.pumpAndSettle();

      expect(_button('Se connecter'), findsOneWidget);
      expect(tokens.value, isNull);
    });

    testWidgets('deleting the account asks for the password, says when it is wrong, then signs out', (tester) async {
      final tokens = MemoryTokenStore('refresh-stored');
      await pumpApp(tester, http, location: '/compte', tokens: tokens);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Supprimer mon compte'));
      await tester.pumpAndSettle();
      expect(find.text('La suppression est définitive. Elle ne peut pas être annulée.'), findsOneWidget);
      expect(find.bySemanticsLabel('Votre mot de passe, pour confirmer'), findsOneWidget);

      api.answers['/api/v1/me/deletion'] = const FakeResponse.json(400, {
        'status': 400,
        'code': 'CURRENT_PASSWORD_INCORRECT',
      });
      await tester.enterText(_field('Votre mot de passe, pour confirmer'), 'pas le bon');
      await tester.tap(find.widgetWithText(FilledButton, 'Supprimer mon compte'));
      await tester.pumpAndSettle();
      expect(find.text('Le mot de passe actuel est incorrect.'), findsOneWidget);
      expect(tokens.value, isNotNull);

      api.answers.remove('/api/v1/me/deletion');
      await tester.enterText(_field('Votre mot de passe, pour confirmer'), 'le bon mot de passe');
      await tester.tap(find.widgetWithText(FilledButton, 'Supprimer mon compte'));
      await tester.pumpAndSettle();

      expect(api.bodies['/api/v1/me/deletion']!.last, {'password': 'le bon mot de passe'});
      expect(tokens.value, isNull);
      expect(find.text('Votre compte a été supprimé'), findsOneWidget);
      expect(_button('Se connecter'), findsOneWidget);
    });

    testWidgets('a session ended by the server says so once, and signs out', (tester) async {
      api.emailVerified = false;
      final tokens = MemoryTokenStore('refresh-stored');
      await pumpApp(tester, http, location: '/compte', tokens: tokens);
      await tester.pumpAndSettle();

      // The access token expires, and the refresh token was revoked elsewhere meanwhile.
      api.validAccess = 'expired';
      api.answers['/api/v1/auth/refresh'] = const FakeResponse.json(401, {
        'status': 401,
        'code': 'INVALID_REFRESH_TOKEN',
      });
      await tester.tap(find.widgetWithText(OutlinedButton, 'Renvoyer le lien'));
      await tester.pumpAndSettle();

      expect(find.text('Votre session a expiré. Reconnectez-vous.'), findsOneWidget);
      expect(_button('Se connecter'), findsOneWidget);
      expect(tokens.value, isNull);

      // Once: going elsewhere and back does not say it again.
      await tester.tap(find.text('Rechercher'));
      await tester.pump(const Duration(seconds: 10));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Compte'));
      await tester.pumpAndSettle();
      expect(find.text('Votre session a expiré. Reconnectez-vous.'), findsNothing);
    });

    testWidgets('an account suspended meanwhile (403 on refresh) is signed out with its own sentence, not “expirée”', (
      tester,
    ) async {
      api.emailVerified = false;
      final tokens = MemoryTokenStore('refresh-stored');
      await pumpApp(tester, http, location: '/compte', tokens: tokens);
      await tester.pumpAndSettle();

      api.validAccess = 'expired';
      api.answers['/api/v1/auth/refresh'] = const FakeResponse.json(403, {'status': 403, 'code': 'ACCOUNT_SUSPENDED'});
      await tester.tap(find.widgetWithText(OutlinedButton, 'Renvoyer le lien'));
      await tester.pumpAndSettle();

      expect(find.text('Ce compte est suspendu. Contactez le support.'), findsOneWidget);
      expect(find.text('Votre session a expiré. Reconnectez-vous.'), findsNothing);
      expect(_button('Se connecter'), findsOneWidget);
      expect(tokens.value, isNull);
    });

    testWidgets('a closed account found at launch (403 on refresh) says so, and shows the sign-in form', (
      tester,
    ) async {
      api.answers['/api/v1/auth/refresh'] = const FakeResponse.json(403, {'status': 403, 'code': 'ACCOUNT_CLOSED'});
      final tokens = MemoryTokenStore('refresh-stored');
      await pumpApp(tester, http, location: '/compte', tokens: tokens);
      await tester.pumpAndSettle();

      expect(find.text('Ce compte a été fermé.'), findsOneWidget);
      expect(find.text('Votre session a expiré. Reconnectez-vous.'), findsNothing);
      expect(_button('Se connecter'), findsOneWidget);
      expect(tokens.value, isNull);
    });
  });

  group('TalkBack', () {
    /// Each label as TalkBack reads it: an exact name, or the start of one (a dropdown reads its
    /// value after its name, a list tile its subtitle after its title).
    Future<void> expectLabels(WidgetTester tester, String location, List<Pattern> labels, {TokenStore? tokens}) async {
      final semantics = tester.ensureSemantics();
      useTallScreen(tester);
      await pumpApp(tester, http, location: location, tokens: tokens);
      await tester.pumpAndSettle();
      for (final label in labels) {
        // The semantics tree itself: a link inside a sentence is a node with no widget of its own.
        expect(find.semantics.byLabel(label), findsAny, reason: '$label');
      }
      semantics.dispose();
    }

    testWidgets('registration: every field, the button and the terms notice', (tester) async {
      await expectLabels(tester, '/inscription', [
        'Nom affiché',
        'Adresse e-mail',
        'Mot de passe',
        'Confirmer le mot de passe',
        'Prénom',
        'Nom',
        'Téléphone',
        RegExp('^Pays'),
        'Créer mon compte',
        // The notice is read whole, and each link is a node of its own.
        RegExp('^En créant un compte, vous acceptez les'),
        'CGU',
        'politique de confidentialité',
      ]);
    });

    testWidgets('forgotten password: the field and the buttons', (tester) async {
      await expectLabels(tester, '/mot-de-passe-oublie', [
        'Adresse e-mail',
        'Envoyer le lien',
        'Retour à la connexion',
      ]);
    });

    testWidgets('the account: its actions', (tester) async {
      api.emailVerified = false;
      await expectLabels(tester, '/compte', [
        'Renvoyer le lien',
        RegExp('^Mes données'),
        RegExp('^Supprimer mon compte'),
        RegExp('^Se déconnecter'),
      ], tokens: MemoryTokenStore('refresh-stored'));
    });
  });

  group('the Messages tab', () {
    testWidgets('signed out, it asks to sign in', (tester) async {
      await pumpApp(tester, http, location: '/messages');
      await tester.pumpAndSettle();

      expect(find.text(AppLabels.messagesSignedOut), findsOneWidget);
      await tester.tap(_button('Se connecter'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(FilledButton, 'Se connecter'), findsOneWidget);
      expect(find.bySemanticsLabel('Adresse e-mail'), findsOneWidget);
    });
  });
}
