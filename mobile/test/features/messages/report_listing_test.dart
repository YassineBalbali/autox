import 'package:autox/core/auth/token_store.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_auth.dart';
import '../../support/fake_http.dart';
import '../../support/fake_messages.dart';
import '../../support/pump_app.dart';

Finder _open() => find.widgetWithText(TextButton, 'Signaler cette annonce');
Finder _submit() => find.widgetWithText(FilledButton, 'Signaler');
const _report = '/api/v1/listings/l1/report';

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

  Future<void> openListing(WidgetTester tester, {bool signedIn = true}) async {
    useTallScreen(tester);
    await pumpApp(tester, http, location: '/annonces/l1', tokens: signedIn ? MemoryTokenStore('refresh-stored') : null);
    await tester.pumpAndSettle();
  }

  Future<void> report(WidgetTester tester, String reason) async {
    await tester.tap(find.text(reason));
    await tester.pump();
    await tester.tap(_submit());
    await tester.pumpAndSettle();
  }

  testWidgets('a reason, then the web’s thanks; the button gives way', (tester) async {
    await openListing(tester);
    await tester.tap(_open());
    await tester.pumpAndSettle();

    expect(find.text('Signaler cette annonce'), findsWidgets);
    for (final reason in ['Arnaque', 'Annonce trompeuse', 'Contenu inapproprié', 'Autre']) {
      expect(find.text(reason), findsOneWidget, reason: reason);
    }
    expect(tester.widget<FilledButton>(_submit()).onPressed, isNull);
    await report(tester, 'Annonce trompeuse');

    expect(messaging.reports[_report], [
      {'reason': 'MISLEADING'},
    ]);
    expect(find.text('Merci : l’annonce a été signalée aux modérateurs.'), findsOneWidget);
    expect(_open(), findsNothing);
  });

  testWidgets('reported on an earlier visit: said so, no button', (tester) async {
    messaging.listingReported = true;
    await openListing(tester);

    expect(find.text('Vous avez signalé cette annonce.'), findsOneWidget);
    expect(_open(), findsNothing);
  });

  testWidgets('already reported, found out on sending (409): the same line, no error', (tester) async {
    await openListing(tester);
    messaging.listingReported = true;
    await tester.tap(_open());
    await tester.pumpAndSettle();
    await report(tester, 'Arnaque');

    expect(find.text('Vous avez signalé cette annonce.'), findsOneWidget);
    expect(find.textContaining('erreur'), findsNothing);
  });

  testWidgets('unverified: the server’s refusal in words, and the sheet stays to try again', (tester) async {
    await openListing(tester);
    messaging.answers[_report] = const FakeResponse.json(403, {'status': 403, 'code': 'EMAIL_NOT_VERIFIED'});
    await tester.tap(_open());
    await tester.pumpAndSettle();
    await report(tester, 'Arnaque');

    expect(
      find.text('Vérifiez d’abord votre adresse e-mail, avec le lien que nous vous avons envoyé.'),
      findsOneWidget,
    );
    expect(_submit(), findsOneWidget);
  });

  testWidgets('signed out: signs in first, then comes back with the form open', (tester) async {
    await openListing(tester, signedIn: false);
    await tester.tap(_open());
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextField, 'Adresse e-mail'), 'jean@exemple.fr');
    await tester.enterText(find.widgetWithText(TextField, 'Mot de passe'), 'motdepasselong');
    await tester.tap(find.widgetWithText(FilledButton, 'Se connecter'));
    await tester.pumpAndSettle();

    expect(find.widgetWithText(AppBar, 'Annonce'), findsOneWidget);
    expect(find.text('Contenu inapproprié'), findsOneWidget);
    await report(tester, 'Contenu inapproprié');
    expect(find.text('Merci : l’annonce a été signalée aux modérateurs.'), findsOneWidget);
  });

  testWidgets('signed out and already reported: back from signing in, the line, not the form', (tester) async {
    messaging.listingReported = true;
    await openListing(tester, signedIn: false);
    await tester.tap(_open());
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Adresse e-mail'), 'jean@exemple.fr');
    await tester.enterText(find.widgetWithText(TextField, 'Mot de passe'), 'motdepasselong');
    await tester.tap(find.widgetWithText(FilledButton, 'Se connecter'));
    await tester.pumpAndSettle();

    expect(find.text('Vous avez signalé cette annonce.'), findsOneWidget);
    expect(find.text('Contenu inapproprié'), findsNothing);
  });

  testWidgets('TalkBack: the button, the reasons as one choice, the note', (tester) async {
    final semantics = tester.ensureSemantics();
    await openListing(tester);
    expect(find.semantics.byLabel('Signaler cette annonce'), findsAny);
    await tester.tap(_open());
    await tester.pumpAndSettle();

    for (final label in ['Arnaque', 'Annonce trompeuse', 'Contenu inapproprié', 'Autre', 'Précisions', 'Annuler']) {
      expect(find.semantics.byLabel(RegExp('^${RegExp.escape(label)}')), findsAny, reason: label);
    }
    semantics.dispose();
  });
}
