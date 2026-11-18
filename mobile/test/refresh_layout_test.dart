import 'dart:convert';

import 'package:autox/core/auth/token_store.dart';
import 'package:autox/features/favourites/favourites.dart';
import 'package:autox_api/autox_api.dart' show PublicListingSummary;
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_auth.dart';
import 'support/fake_http.dart';
import 'support/fake_messages.dart';
import 'support/listing_detail.dart';
import 'support/listings.dart';
import 'support/pump_app.dart';

/// The screens of the v1.1 refresh's second phase on a small phone with the system text at its
/// largest: nothing overflows, whatever wraps.
void _smallPhoneLargeText(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400); // 360 × 800 dp
  tester.view.devicePixelRatio = 3;
  tester.platformDispatcher.textScaleFactorTestValue = 2;
  addTearDown(tester.view.reset);
  addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
}

/// Scrolls [scrollable] to its end, a screen at a time, building every part on the way.
Future<void> _scrollThrough(WidgetTester tester, Finder scrollable) async {
  for (var i = 0; i < 30; i++) {
    final position = tester.state<ScrollableState>(scrollable).position;
    if (position.pixels >= position.maxScrollExtent) {
      break;
    }
    await tester.drag(scrollable, const Offset(0, -600));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  }
}

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

  group('360 dp wide, text at 200 %', () {
    testWidgets('a listing’s page, from the photos to the similar listings', (tester) async {
      _smallPhoneLargeText(tester);
      await pumpApp(
        tester,
        FakeHttp({
          '/api/v1/listings/l1': FakeResponse.json(
            200,
            detailJson(
              images: [
                {
                  'id': 'i1',
                  'position': 0,
                  'variants': {
                    'CARD': {'url': 'http://localhost:9000/1/card.jpg', 'width': 640, 'height': 480},
                  },
                },
                {
                  'id': 'i2',
                  'position': 1,
                  'variants': {
                    'CARD': {'url': 'http://localhost:9000/2/card.jpg', 'width': 640, 'height': 480},
                  },
                },
              ],
            ),
          ),
          '/api/v1/listings/l1/similar': FakeResponse.json(200, [listingJson(id: 'l2', priceLevel: 'MARKET')]),
          '/api/v1/reference/features': const FakeResponse.json(200, featuresJson),
        }),
        location: '/annonces/l1',
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);

      await _scrollThrough(tester, find.byType(Scrollable).first);
      expect(find.text('Annonces similaires'), findsOneWidget);
    });

    testWidgets('the Favoris tab, a listing as it is and one gone offline', (tester) async {
      _smallPhoneLargeText(tester);
      String entry(String id, int minute) => jsonEncode(
        Favourite(
          id: id,
          savedAt: DateTime.utc(2026, 11, 1, 12, minute),
          listing: PublicListingSummary.fromJson(listingJson(id: id, priceLevel: 'GOOD_DEAL', photoCount: 4)),
        ).toJson(),
      );
      await pumpApp(
        tester,
        FakeHttp({
          '/api/v1/listings/l1': FakeResponse.json(200, detailJson()),
          '/api/v1/listings/sold': const FakeResponse.json(404, {'status': 404, 'code': 'NOT_FOUND'}),
        }),
        location: '/favoris',
        stored: {favouritesKey: '[${entry('sold', 2)},${entry('l1', 1)}]'},
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Annonce retirée'), findsOneWidget);

      await _scrollThrough(tester, find.byType(Scrollable).first);
    });

    testWidgets('the empty Favoris tab', (tester) async {
      _smallPhoneLargeText(tester);
      await pumpApp(tester, FakeHttp({}), location: '/favoris');
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Rechercher une voiture'), findsOneWidget);
    });

    testWidgets('sign-in and registration', (tester) async {
      _smallPhoneLargeText(tester);
      await pumpApp(tester, FakeHttp({}), location: '/connexion');
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await _scrollThrough(tester, find.byType(Scrollable).first);

      await tester.tap(find.text('Créer un compte'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await _scrollThrough(tester, find.byType(Scrollable).first);
    });

    group('signed in', () {
      late FakeHttp http;
      setUp(() {
        final accounts = FakeAccountApi(emailVerified: false);
        http = (FakeMessaging(accounts)..conversation()).http();
      });

      testWidgets('the inbox', (tester) async {
        _smallPhoneLargeText(tester);
        await pumpApp(tester, http, location: '/messages', tokens: MemoryTokenStore('refresh-stored'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.text('Messages'), findsWidgets);
      });

      testWidgets('a conversation, with its composer', (tester) async {
        _smallPhoneLargeText(tester);
        await pumpApp(tester, http, location: '/messages/c1', tokens: MemoryTokenStore('refresh-stored'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      });

      testWidgets('the account, unverified: the notice, then the grouped cards', (tester) async {
        _smallPhoneLargeText(tester);
        await pumpApp(tester, http, location: '/compte', tokens: MemoryTokenStore('refresh-stored'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await _scrollThrough(tester, find.byType(Scrollable).first);
        expect(find.text('Supprimer mon compte'), findsOneWidget);
      });
    });
  });
}
