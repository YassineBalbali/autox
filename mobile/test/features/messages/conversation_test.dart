import 'dart:async';

import 'package:autox/core/auth/token_store.dart';
import 'package:flutter/semantics.dart' show SemanticsFlag;
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_auth.dart';
import '../../support/fake_http.dart';
import '../../support/fake_messages.dart';
import '../../support/pump_app.dart';

Finder _composer() => find.widgetWithText(TextField, 'Votre réponse');
Finder _send() => find.ancestor(of: find.byIcon(LucideIcons.send), matching: find.byType(IconButton));
Finder _reportButton() => find.widgetWithText(TextButton, 'Signaler');
const _conversation = '/api/v1/conversations/c1';
const _reply = '/api/v1/conversations/c1/messages';

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

  late FakeAccountApi accounts;
  late FakeMessaging messaging;
  late FakeHttp http;

  setUp(() {
    accounts = FakeAccountApi();
    messaging = FakeMessaging(accounts)..conversation();
    http = messaging.http();
  });

  Future<void> open(WidgetTester tester, {String location = '/messages/c1'}) async {
    await pumpApp(tester, http, location: location, tokens: MemoryTokenStore('refresh-stored'));
    await tester.pumpAndSettle();
  }

  IconButton sendButton(WidgetTester tester) => tester.widget<IconButton>(_send());

  Future<void> type(WidgetTester tester, String text) async {
    await tester.enterText(_composer(), text);
    await tester.pump();
  }

  /// The app's lifecycle, as Android moves it (each state in turn, as the listeners expect).
  void moveTo(WidgetTester tester, List<AppLifecycleState> states) {
    for (final state in states) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
  }

  group('the conversation', () {
    testWidgets('opens marked read, oldest message first, with the composer', (tester) async {
      await open(tester);

      expect(find.text('Bonjour, toujours intéressé ?'), findsOneWidget);
      expect(find.text('vendeur de cette annonce'), findsOneWidget);
      expect(http.to('$_conversation/read'), hasLength(1));
      expect(_composer(), findsOneWidget);
      expect(find.text('Ne payez jamais un véhicule avant de l’avoir vu.'), findsOneWidget);
    });

    testWidgets('the composer keeps its name for TalkBack once its placeholder gives way to the text', (tester) async {
      final semantics = tester.ensureSemantics();
      await open(tester);
      await tester.enterText(_composer(), 'Je passe samedi.');
      await tester.pump();

      final field = tester.getSemantics(find.byType(EditableText));
      expect(field.label, contains('Votre réponse'));
      expect(field.value, 'Je passe samedi.');
      // The send button, a 48 dp target.
      expect(tester.getSize(find.byTooltip('Envoyer')).height, greaterThanOrEqualTo(48));
      semantics.dispose();
    });

    testWidgets('a message is shown as typed: markup stays text, links are not made tappable', (tester) async {
      messaging.conversation(
        messages: [messaging.message('<b>Gras</b> https://exemple.fr', mine: false, id: 'm-seller')],
      );
      await open(tester);

      expect(find.text('<b>Gras</b> https://exemple.fr'), findsOneWidget);
    });

    testWidgets('closed: readable, nothing to write in', (tester) async {
      messaging.conversation(canReply: false);
      await open(tester);

      expect(find.textContaining('Cette conversation est fermée'), findsOneWidget);
      expect(_composer(), findsNothing);
    });

    testWidgets('unverified: the web’s notice instead of the composer', (tester) async {
      accounts.emailVerified = false;
      await open(tester);

      expect(find.textContaining('Vérifiez votre adresse e-mail pour répondre'), findsOneWidget);
      expect(_composer(), findsNothing);
    });

    testWidgets('signed out, a conversation’s address is the tab’s "Connectez-vous"', (tester) async {
      await pumpApp(tester, http, location: '/messages/c1');
      await tester.pumpAndSettle();

      expect(find.text('Connectez-vous pour voir vos messages'), findsOneWidget);
      expect(http.to(_conversation), isEmpty);
    });
  });

  group('sending', () {
    testWidgets('pending disables the button; a failure keeps the text; nothing is resent on its own', (tester) async {
      await open(tester);
      final gate = messaging.gates[_reply] = Completer<void>();
      messaging.answers[_reply] = const FakeResponse.json(500, {'status': 500, 'code': 'INTERNAL_ERROR'});

      await type(tester, 'Je passe samedi.');
      expect(sendButton(tester).onPressed, isNotNull);
      await tester.tap(_send());
      await tester.pump();
      expect(sendButton(tester).onPressed, isNull);

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('Une erreur inattendue s’est produite. Réessayez dans un instant.'), findsOneWidget);
      expect(find.text('Je passe samedi.'), findsOneWidget);
      expect(sendButton(tester).onPressed, isNotNull);

      // A minute later, still one attempt: trying again is the person's to do.
      await tester.pump(const Duration(minutes: 1));
      expect(http.to(_reply), hasLength(1));

      messaging.answers.remove(_reply);
      messaging.gates.remove(_reply);
      await tester.tap(_send());
      await tester.pumpAndSettle();
      expect(messaging.accepted, ['Je passe samedi.']);
      expect(find.widgetWithText(TextField, 'Je passe samedi.'), findsNothing);
      expect(find.text('Je passe samedi.'), findsOneWidget);
    });

    testWidgets('a 401 while sending goes through the one refresh, and the message is sent exactly once', (
      tester,
    ) async {
      await open(tester);
      final refreshes = http.to('/api/v1/auth/refresh').length;
      accounts.validAccess = 'expired-meanwhile';

      await type(tester, 'Toujours là ?');
      await tester.tap(_send());
      await tester.pumpAndSettle();

      expect(http.to('/api/v1/auth/refresh'), hasLength(refreshes + 1));
      // Refused once with the old token (nothing written), then accepted with the new one.
      expect(http.to(_reply), hasLength(2));
      expect(messaging.accepted, ['Toujours là ?']);
    });

    testWidgets('too many messages: the writer’s words, with the wait', (tester) async {
      await open(tester);
      messaging.answers[_reply] = const FakeResponse.json(429, {
        'status': 429,
        'code': 'RATE_LIMITED',
        'retryAfterSeconds': 120,
      });
      await type(tester, 'Encore un');
      await tester.tap(_send());
      await tester.pumpAndSettle();

      expect(
        find.text('Vous avez envoyé beaucoup de messages en peu de temps. Réessayez dans 2 minutes.'),
        findsOneWidget,
      );
    });
  });

  group('polling', () {
    testWidgets('every 15 s on screen; not in the background; again at once on return; never once left', (
      tester,
    ) async {
      await open(tester, location: '/messages');
      await tester.tap(find.text('Garage Bellevue'));
      await tester.pumpAndSettle();
      int reads() => http.to(_conversation).length;
      final opened = reads();

      await tester.pump(const Duration(seconds: 15));
      expect(reads(), opened + 1);

      // Something arrives: it shows up on the next poll, and is marked read.
      (messaging.conversations['c1']!['messages']! as List).add(messaging.message('Oui, samedi 10 h.', mine: false));
      final marked = http.to('$_conversation/read').length;
      await tester.pump(const Duration(seconds: 15));
      await tester.pumpAndSettle();
      expect(find.text('Oui, samedi 10 h.'), findsOneWidget);
      expect(http.to('$_conversation/read'), hasLength(marked + 1));

      final beforeBackground = reads();
      moveTo(tester, [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]);
      await tester.pump(const Duration(minutes: 1));
      expect(reads(), beforeBackground);

      moveTo(tester, [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]);
      await tester.pump(const Duration(milliseconds: 10));
      expect(reads(), beforeBackground + 1);
      await tester.pump(const Duration(seconds: 15));
      expect(reads(), beforeBackground + 2);

      // Left: back to the inbox, and no more reads.
      // Android's back.
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      final left = reads();
      await tester.pump(const Duration(minutes: 1));
      expect(reads(), left);
    });

    testWidgets('behind another screen (the listing opened from it), it stops; back on top, it goes on', (
      tester,
    ) async {
      await open(tester);
      int reads() => http.to(_conversation).length;

      await tester.tap(find.text('Peugeot 3008 1.5 BlueHDi 130 Allure'));
      await tester.pumpAndSettle();
      final covered = reads();
      await tester.pump(const Duration(seconds: 45));
      expect(reads(), covered);

      // Android's back.
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 15));
      expect(reads(), covered + 1);
    });
  });

  group('the Messages tab’s badge', () {
    testWidgets('the unread count, asked again on resume and when the tab is selected', (tester) async {
      messaging.unread = 2;
      await open(tester, location: '/recherche');
      expect(find.text('2'), findsOneWidget);

      messaging.unread = 5;
      moveTo(tester, [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]);
      moveTo(tester, [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]);
      await tester.pumpAndSettle();
      expect(find.text('5'), findsOneWidget);

      messaging.unread = 1;
      await tester.tap(find.text('Messages'));
      await tester.pumpAndSettle();
      expect(find.text('1'), findsWidgets);
      final semantics = tester.ensureSemantics();
      await tester.pump();
      expect(find.semantics.byLabel(RegExp('1 conversation non lue')), findsAny);
      semantics.dispose();
    });

    testWidgets('signed out, no badge and no count asked for', (tester) async {
      messaging.unread = 3;
      await pumpApp(tester, http);
      await tester.pumpAndSettle();

      expect(find.byType(Badge), findsNothing);
      expect(http.to('/api/v1/conversations/unread'), isEmpty);
    });
  });

  group('the inbox', () {
    testWidgets('who, about which listing, the last message, and the unread marker', (tester) async {
      final semantics = tester.ensureSemantics();
      await open(tester, location: '/messages');

      expect(find.text('Garage Bellevue'), findsOneWidget);
      expect(find.text('Peugeot 3008 1.5 BlueHDi 130 Allure'), findsOneWidget);
      expect(find.text('Bonjour, toujours intéressé ?'), findsOneWidget);
      expect(find.semantics.byLabel(RegExp('1 message non lu')), findsOne);
      semantics.dispose();
    });

    testWidgets('as the seller: "Votre annonce ·", and the buyer’s side', (tester) async {
      messaging.conversation(role: 'SELLER');
      await open(tester, location: '/messages');

      expect(find.text('Votre annonce · Peugeot 3008 1.5 BlueHDi 130 Allure'), findsOneWidget);
    });
  });

  group('reporting a message', () {
    testWidgets('the other side’s messages only; a reason; then "Signalé"', (tester) async {
      messaging.conversation(
        messages: [
          messaging.message('Payez par mandat cash', mine: false, id: 'm-seller'),
          messaging.message('Pourquoi ?', mine: true),
        ],
      );
      await open(tester);
      expect(_reportButton(), findsOneWidget);

      await tester.tap(_reportButton());
      await tester.pumpAndSettle();
      final submit = find.widgetWithText(FilledButton, 'Signaler');
      expect(tester.widget<FilledButton>(submit).onPressed, isNull);
      await tester.tap(find.text('Arnaque ou demande de paiement suspecte'));
      await tester.pump();
      await tester.tap(submit);
      await tester.pumpAndSettle();

      expect(messaging.reports['/api/v1/messages/m-seller/report'], [
        {'reason': 'SCAM'},
      ]);
      expect(find.text('Merci : le message a été signalé aux modérateurs.'), findsOneWidget);
      expect(_reportButton(), findsNothing);
      expect(find.textContaining('Signalé'), findsOneWidget);
    });

    testWidgets('"Autre raison" needs a note', (tester) async {
      await open(tester);
      await tester.tap(_reportButton());
      await tester.pumpAndSettle();
      final submit = find.widgetWithText(FilledButton, 'Signaler');

      await tester.tap(find.text('Autre raison'));
      await tester.pump();
      expect(find.text('Expliquez ce qui ne va pas (obligatoire)'), findsOneWidget);
      expect(tester.widget<FilledButton>(submit).onPressed, isNull);
      await tester.enterText(find.widgetWithText(TextField, 'Précisions'), 'Il insiste pour un acompte.');
      await tester.pump();
      await tester.tap(submit);
      await tester.pumpAndSettle();

      expect(messaging.reports['/api/v1/messages/m-seller/report'], [
        {'reason': 'OTHER', 'note': 'Il insiste pour un acompte.'},
      ]);
    });

    testWidgets('already reported: "Signalé", no button', (tester) async {
      messaging.conversation(
        messages: [messaging.message('Payez par mandat cash', mine: false, id: 'm-seller', reportedByMe: true)],
      );
      await open(tester);

      expect(_reportButton(), findsNothing);
      expect(find.textContaining('Signalé'), findsOneWidget);
    });
  });

  group('TalkBack', () {
    testWidgets('a bubble says who and when; the composer and the send button are named', (tester) async {
      final semantics = tester.ensureSemantics();
      await open(tester);

      expect(
        find.semantics.byLabel(
          RegExp(r'^Garage Bellevue : Bonjour, toujours intéressé \?, \w+ \d+ \w+ \d{4} à \d\d:\d\d$'),
        ),
        findsOne,
      );
      expect(find.semantics.byLabel('Votre réponse'), findsAny);
      expect(find.semantics.byLabel('Envoyer'), findsAny);
      expect(find.semantics.byLabel('Signaler ce message'), findsOne);
      semantics.dispose();
    });

    testWidgets('the report sheet: its title, each reason, the note and the buttons', (tester) async {
      final semantics = tester.ensureSemantics();
      await open(tester);
      await tester.tap(_reportButton());
      await tester.pumpAndSettle();

      for (final label in [
        'Signaler ce message',
        'Arnaque ou demande de paiement suspecte',
        'Propos injurieux ou menaçants',
        'Publicité ou message envoyé en masse',
        'Autre raison',
        'Précisions',
        'Signaler',
        'Annuler',
      ]) {
        expect(find.semantics.byLabel(RegExp('^${RegExp.escape(label)}')), findsAny, reason: label);
      }
      // The four reasons are one choice: radio buttons, read as such.
      expect(find.semantics.byFlag(SemanticsFlag.isInMutuallyExclusiveGroup), findsExactly(4));
      semantics.dispose();
    });
  });
}
