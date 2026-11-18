import 'dart:convert';
import 'dart:io';

import 'package:autox/core/format/numbers.dart';
import 'package:autox/features/favourites/favourite_button.dart';
import 'package:autox/features/favourites/favourites.dart';
import 'package:autox/features/listing/widgets/listing_card.dart';
import 'package:autox/features/search/recent_searches.dart' show sharedPreferencesProvider;
import 'package:autox_api/autox_api.dart' show PublicListingDetail, PublicListingSummary;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../support/fake_http.dart';
import '../../support/listing_detail.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

PublicListingSummary _summary(String id, {int price = 2249000}) =>
    PublicListingSummary.fromJson(listingJson(id: id, priceAmount: price));

/// What the phone keeps for [ids], saved one after the other, the last one first.
String _stored(List<String> ids) => jsonEncode(
  [
    for (final (index, id) in ids.indexed)
      Favourite(id: id, savedAt: DateTime.utc(2026, 11, 1, 12, index), listing: _summary(id)).toJson(),
  ].reversed.toList(),
);

const _gone = FakeResponse.json(404, {'status': 404, 'code': 'NOT_FOUND'});

void main() {
  test('the cap is the web’s, read from favourites.ts', () {
    final source = File('../frontend/src/app/shared/listing/favourites.ts').readAsStringSync();
    final match = RegExp(r'FAVOURITES_LIMIT\s*=\s*(\d+)').firstMatch(source);
    expect(match, isNotNull, reason: 'favourites.ts no longer declares FAVOURITES_LIMIT');
    expect(favouritesLimit, int.parse(match!.group(1)!));
  });

  group('the store', () {
    Future<(ProviderContainer, SharedPreferences)> store([Map<String, Object> stored = const {}]) async {
      final preferences = await testPreferences(stored);
      final container = ProviderContainer(overrides: [sharedPreferencesProvider.overrideWithValue(preferences)]);
      addTearDown(container.dispose);
      return (container, preferences);
    }

    test('a toggle saves the listing first, a second forgets it, and both are written', () async {
      final (container, preferences) = await store();
      final favourites = container.read(favouritesProvider.notifier);

      expect(favourites.toggle(_summary('a')), isTrue);
      expect(favourites.toggle(_summary('b')), isTrue);
      expect(container.read(favouritesProvider).map((f) => f.id), ['b', 'a']);
      expect(container.read(isFavouriteProvider('a')), isTrue);

      expect(favourites.toggle(_summary('a')), isFalse);
      expect(container.read(isFavouriteProvider('a')), isFalse);
      final written = jsonDecode(preferences.getString(favouritesKey)!) as List<Object?>;
      expect(written.map((entry) => (entry! as Map)['id']), ['b']);
    });

    test('past the cap, the oldest goes', () async {
      final (container, _) = await store();
      final favourites = container.read(favouritesProvider.notifier);
      for (var i = 0; i <= favouritesLimit; i++) {
        favourites.toggle(_summary('l-$i'));
      }

      final saved = container.read(favouritesProvider);
      expect(saved, hasLength(favouritesLimit));
      expect(saved.first.id, 'l-$favouritesLimit');
      expect(saved.any((f) => f.id == 'l-0'), isFalse);
    });

    test('what is stored is checked, not trusted', () async {
      final (container, _) = await store({
        favouritesKey: jsonEncode([
          'l-web-style-id',
          {'id': 'no-date', 'listing': listingJson()},
          {'id': 'ok', 'savedAt': '2026-11-01T12:00:00Z', 'listing': listingJson(id: 'ok')},
          {'id': 'ok', 'savedAt': '2026-11-02T12:00:00Z', 'listing': listingJson(id: 'ok')},
        ]),
      });
      expect(container.read(favouritesProvider).map((f) => f.id), ['ok']);

      final (broken, _) = await store({favouritesKey: '{not json'});
      expect(broken.read(favouritesProvider), isEmpty);
    });

    test('a listing’s page becomes the card it is saved as', () {
      final detail = PublicListingDetail.fromJson(
        detailJson(
          images: [
            {
              'id': 'i2',
              'position': 1,
              'variants': {
                'CARD': {'url': 'http://localhost:9000/2/card.jpg', 'width': 640, 'height': 480},
              },
            },
            {
              'id': 'i1',
              'position': 0,
              'variants': {
                'CARD': {'url': 'http://localhost:9000/1/card.jpg', 'width': 640, 'height': 480},
                'THUMBNAIL': {'url': 'http://localhost:9000/1/thumb.jpg', 'width': 320, 'height': 240},
              },
            },
          ],
        ),
      );

      final summary = summaryOfDetail(detail);
      expect(summary.id, 'l1');
      expect(summary.make, 'Peugeot');
      expect(summary.fuelType, 'DIESEL');
      expect(summary.city, 'Lyon');
      expect(summary.seller?.role, 'PROFESSIONNEL');
      expect(summary.photoCount, 2);
      expect(summary.coverImage?['CARD']?.url, 'http://localhost:9000/1/card.jpg');
      expect(summary.priceIndicator?.level, detail.priceIndicator?.level);
    });
  });

  group('the heart on a result', () {
    FakeHttp http() => FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 1, 'capped': false}),
      '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson(id: 'l1')])),
      '/api/v1/listings/l1': FakeResponse.json(200, detailJson()),
    });

    testWidgets('saves the listing, says so, and TalkBack hears a toggle named after it', (tester) async {
      final semantics = tester.ensureSemantics();
      await pumpApp(tester, http(), location: '/recherche/resultats');
      await tester.pumpAndSettle();

      final heart = find.bySemanticsLabel('Ajouter Peugeot 3008 aux favoris');
      expect(heart, findsOneWidget);
      expect(tester.getSemantics(heart).flagsCollection.isToggled.toBoolOrNull(), isFalse);
      expect(tester.getSize(find.byType(FavouriteButton)).height, greaterThanOrEqualTo(48));

      await tester.tap(find.byType(FavouriteButton));
      await tester.pump();
      expect(find.text('Ajoutée à vos favoris'), findsOneWidget);
      final saved = find.bySemanticsLabel('Retirer Peugeot 3008 des favoris');
      expect(tester.getSemantics(saved).flagsCollection.isToggled.toBoolOrNull(), isTrue);

      // On the Favoris tab.
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Favoris')));
      await tester.pumpAndSettle();
      expect(find.byType(ListingCard), findsOneWidget);
      expect(find.text('1 annonce gardée sur ce téléphone'), findsOneWidget);

      // Unmarked from there: the tab is empty again.
      await tester.tap(find.byType(FavouriteButton));
      await tester.pumpAndSettle();
      expect(find.text('Retirée de vos favoris'), findsOneWidget);
      expect(find.text('Aucun favori pour l’instant'), findsOneWidget);
      semantics.dispose();
    });

    testWidgets('a listing saved before a restart is still there after it', (tester) async {
      await pumpApp(tester, http(), location: '/recherche/resultats');
      await tester.pumpAndSettle();
      await tester.tap(find.byType(FavouriteButton));
      await tester.pumpAndSettle();
      final kept = (await SharedPreferences.getInstance()).getString(favouritesKey)!;

      // A new start: a new app, the phone's preferences as they were left.
      await tester.pumpWidget(const SizedBox());
      await pumpApp(tester, http(), location: '/favoris', stored: {favouritesKey: kept});
      await tester.pumpAndSettle();

      expect(find.byType(ListingCard), findsOneWidget);
      expect(find.bySemanticsLabel('Retirer Peugeot 3008 des favoris'), findsOneWidget);
    });
  });

  group('the Favoris tab', () {
    testWidgets('empty: says how to save one, and leads to the search', (tester) async {
      await pumpApp(
        tester,
        FakeHttp({
          '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
          '/api/v1/listings/count': const FakeResponse.json(200, {'count': 1, 'capped': false}),
        }),
        location: '/favoris',
      );
      await tester.pumpAndSettle();

      expect(find.text('Aucun favori pour l’instant'), findsOneWidget);
      await tester.tap(find.text('Rechercher une voiture'));
      await tester.pumpAndSettle();
      expect(find.text('Trouvez votre voiture'), findsOneWidget);
    });

    testWidgets('newest saved first, each card as the listing is now', (tester) async {
      useTallScreen(tester);
      await pumpApp(
        tester,
        FakeHttp({
          '/api/v1/listings/old': FakeResponse.json(200, detailJson(id: 'old', priceAmount: 1000000)),
          // Its price went down since it was saved.
          '/api/v1/listings/new': FakeResponse.json(200, detailJson(id: 'new', priceAmount: 1990000)),
        }),
        location: '/favoris',
        stored: {
          favouritesKey: _stored(['old', 'new']),
        },
      );
      await tester.pumpAndSettle();

      final cards = tester.widgetList<ListingCard>(find.byType(ListingCard)).toList();
      expect(cards.map((card) => card.listing.id), ['new', 'old']);
      expect(find.text(formatPrice(1990000)), findsOneWidget);
      expect(find.text('2 annonces gardées sur ce téléphone'), findsOneWidget);
    });

    testWidgets('a listing gone offline is greyed, "Annonce retirée", and can be removed', (tester) async {
      final semantics = tester.ensureSemantics();
      await pumpApp(
        tester,
        FakeHttp({'/api/v1/listings/sold': _gone}),
        location: '/favoris',
        stored: {
          favouritesKey: _stored(['sold']),
        },
      );
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Annonce retirée'), findsOneWidget);
      expect(find.byType(ListingCard), findsNothing);
      final remove = find.bySemanticsLabel('Retirer Peugeot 3008 des favoris');
      expect(remove, findsOneWidget);

      await tester.tap(find.text('Retirer'));
      await tester.pumpAndSettle();
      expect(find.text('Aucun favori pour l’instant'), findsOneWidget);
      expect((await SharedPreferences.getInstance()).getString(favouritesKey), '[]');
      semantics.dispose();
    });

    testWidgets('without a network, the saved version stays', (tester) async {
      await pumpApp(
        tester,
        FakeHttp({
          '/api/v1/listings/l1': const FakeResponse.json(503, {'status': 503}),
        }),
        location: '/favoris',
        stored: {
          favouritesKey: _stored(['l1']),
        },
      );
      await tester.pumpAndSettle();

      expect(find.byType(ListingCard), findsOneWidget);
      expect(find.text('Annonce retirée'), findsNothing);
      expect(find.text(formatPrice(2249000)), findsOneWidget);
    });
  });
}
