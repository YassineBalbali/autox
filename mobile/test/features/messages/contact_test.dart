import 'package:autox/core/auth/token_store.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_auth.dart';
import '../../support/fake_http.dart';
import '../../support/fake_messages.dart';
import '../../support/listing_detail.dart';
import '../../support/pump_app.dart';

Finder _field(String label) => find.widgetWithText(TextField, label);
Finder _contact() => find.widgetWithText(FilledButton, 'Contacter le vendeur');
const _greeting =
    'Bonjour, votre annonce « Peugeot 3008 1.5 BlueHDi 130 Allure » m’intéresse. Est-elle toujours disponible ?';

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

  late FakeAccountApi accounts;
  late FakeMessaging messaging;
  late FakeHttp http;

  setUp(() {
    accounts = FakeAccountApi();
    messaging = FakeMessaging(accounts);
    http = messaging.http();
  });

  Future<void> openListing(WidgetTester tester, {TokenStore? tokens}) async {
    useTallScreen(tester);
    await pumpApp(tester, http, location: '/annonces/l1', tokens: tokens);
    await tester.pumpAndSettle();
  }

  Future<void> signIn(WidgetTester tester) async {
    await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
    await tester.enterText(_field('Mot de passe'), 'motdepasselong');
    await tester.tap(find.widgetWithText(FilledButton, 'Se connecter'));
    await tester.pumpAndSettle();
  }

  group('signed out', () {
    testWidgets('"Contacter le vendeur" signs in, then comes back to the listing with the message open', (
      tester,
    ) async {
      await openListing(tester);
      expect(find.textContaining('Connectez-vous pour écrire au vendeur'), findsOneWidget);

      await tester.tap(_contact());
      await tester.pumpAndSettle();
      expect(find.widgetWithText(AppBar, 'Se connecter'), findsOneWidget);

      await signIn(tester);

      // The listing again, under the open sheet, with the first line written.
      expect(find.text('Écrire à Garage Bellevue'), findsOneWidget);
      expect(find.text(_greeting), findsOneWidget);
      expect(find.widgetWithText(AppBar, 'Annonce'), findsOneWidget);
      expect(http.to('/api/v1/listings/l1/conversation'), isEmpty);

      // Back from the listing is where the person came from, not the sign-in form.
      await tester.tap(find.widgetWithText(TextButton, 'Annuler'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(AppBar, 'Se connecter'), findsNothing);
    });

    testWidgets('registering instead comes back too; an unverified address is told to verify first', (tester) async {
      accounts.emailVerified = false;
      await openListing(tester);
      await tester.tap(_contact());
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(OutlinedButton, 'Créer un compte'));
      await tester.pumpAndSettle();

      await tester.enterText(_field('Nom affiché'), 'Jean D.');
      await tester.enterText(_field('Adresse e-mail'), 'jean@exemple.fr');
      await tester.enterText(_field('Mot de passe'), 'unmotdepasselong');
      await tester.enterText(_field('Confirmer le mot de passe'), 'unmotdepasselong');
      await tester.tap(find.widgetWithText(FilledButton, 'Créer mon compte'));
      await tester.pumpAndSettle();

      expect(find.widgetWithText(AppBar, 'Annonce'), findsOneWidget);
      expect(find.text('Écrire à Garage Bellevue'), findsNothing);
      expect(find.textContaining('Vérifiez votre adresse e-mail pour écrire au vendeur'), findsOneWidget);
      expect(_contact(), findsNothing);
    });
  });

  group('signed in', () {
    testWidgets('unverified: explains, offers "Renvoyer le lien", and never tries to send', (tester) async {
      accounts.emailVerified = false;
      await openListing(tester, tokens: MemoryTokenStore('refresh-stored'));

      expect(find.textContaining('Vérifiez votre adresse e-mail pour écrire au vendeur'), findsOneWidget);
      expect(_contact(), findsNothing);
      await tester.tap(find.widgetWithText(OutlinedButton, 'Renvoyer le lien'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Nouveau lien envoyé à jean@exemple.fr'), findsOneWidget);
      expect(http.to('/api/v1/listings/l1/conversation'), isEmpty);
    });

    testWidgets('their own listing: no message, a pointer to their messages', (tester) async {
      http.routes['/api/v1/listings/l1'] = FakeResponse.json(200, detailJson(sellerId: 'u1'));
      await openListing(tester, tokens: MemoryTokenStore('refresh-stored'));

      expect(find.textContaining('C’est votre annonce. Les messages des acheteurs arrivent dans'), findsOneWidget);
      expect(_contact(), findsNothing);
      expect(find.text('Signaler cette annonce'), findsNothing);
    });

    testWidgets('a removed listing (or a suspended seller’s): the page says it is gone, nothing to write', (
      tester,
    ) async {
      http.routes['/api/v1/listings/l1'] = const FakeResponse.json(404, {'status': 404, 'code': 'NOT_FOUND'});
      await openListing(tester, tokens: MemoryTokenStore('refresh-stored'));

      expect(_contact(), findsNothing);
      expect(find.text('Écrire à Garage Bellevue'), findsNothing);
    });

    testWidgets('taken down while writing: the web’s sentence, and the text is kept', (tester) async {
      await openListing(tester, tokens: MemoryTokenStore('refresh-stored'));
      messaging.answers['/api/v1/listings/l1/conversation'] = const FakeResponse.json(404, {
        'status': 404,
        'code': 'NOT_FOUND',
      });
      await tester.tap(_contact());
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Envoyer'));
      await tester.pumpAndSettle();

      expect(
        find.text('Cette annonce n’est plus en ligne : il n’est plus possible d’écrire au vendeur.'),
        findsOneWidget,
      );
      expect(find.text(_greeting), findsOneWidget);
    });

    testWidgets('a conversation about this listing already exists: the message goes there, and it opens', (
      tester,
    ) async {
      messaging.conversation();
      await openListing(tester, tokens: MemoryTokenStore('refresh-stored'));

      await tester.tap(_contact());
      await tester.pumpAndSettle();
      await tester.enterText(_field('Votre message'), 'Je peux passer samedi ?');
      await tester.tap(find.widgetWithText(FilledButton, 'Envoyer'));
      await tester.pumpAndSettle();

      expect(find.text('Message envoyé. La réponse du vendeur arrivera dans vos messages.'), findsOneWidget);
      expect(messaging.conversations.keys, ['c1']);
      expect(messaging.accepted, ['Je peux passer samedi ?']);

      await tester.tap(find.widgetWithText(OutlinedButton, 'Voir la conversation'));
      await tester.pumpAndSettle();
      // The earlier message and the new one, in the one conversation.
      expect(find.text('Bonjour, toujours intéressé ?'), findsOneWidget);
      expect(find.text('Je peux passer samedi ?'), findsOneWidget);
    });

    testWidgets('a demo seller: told before writing that nobody will answer', (tester) async {
      http.routes['/api/v1/listings/l1'] = FakeResponse.json(200, detailJson(demo: true));
      await openListing(tester, tokens: MemoryTokenStore('refresh-stored'));

      expect(find.text('Ce vendeur fait partie de la démonstration et ne répondra pas aux messages.'), findsOneWidget);
    });
  });
}
