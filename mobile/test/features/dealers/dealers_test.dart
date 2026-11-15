import 'package:autox/core/links.dart';
import 'package:autox/features/dealers/dealer_screen.dart';
import 'package:autox/features/dealers/dealer_text.dart';
import 'package:autox/features/listing/widgets/listing_row.dart';
import 'package:autox_api/autox_api.dart' show DealerCard;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

Map<String, Object?> _card(String slug, String name, {int sale = 12, int leasing = 0}) => {
  'slug': slug,
  'name': name,
  'city': 'Lyon',
  'postalCode': '69003',
  'departement': '69',
  'saleListings': sale,
  'leasingListings': leasing,
  'memberSince': '2019-03-15T12:00:00Z',
};

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

  late FakeHttp http;

  setUp(() {
    http = FakeHttp(
      {
        '/api/v1/dealers/garage-bellevue': const FakeResponse.json(200, {
          'slug': 'garage-bellevue',
          'sellerId': 's1',
          'name': 'Garage Bellevue',
          'city': 'Lyon',
          'departement': '69',
          'description': 'Concession familiale depuis 1982.',
          'openingHours': 'Lundi–samedi, 9 h – 19 h',
          'websiteUrl': 'https://garage-bellevue.fr/',
          'phone': '+33 4 78 00 00 00',
          'memberSince': '2019-03-15T12:00:00Z',
          'saleListings': 12,
          'leasingListings': 3,
        }),
        '/api/v1/dealers/gone': const FakeResponse.json(404, {'status': 404}),
        '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson()])),
        '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
      },
      handlers: {
        '/api/v1/dealers': (request) {
          final page = int.parse(request.uri.queryParameters['page'] ?? '0');
          return FakeResponse.json(200, {
            'items': [
              if (page == 0) ...[
                _card('garage-bellevue', 'Garage Bellevue', leasing: 3),
                _card('auto-sud', 'Auto Sud'),
              ],
              if (page == 1) _card('dernier', 'Le Dernier', sale: 1),
            ],
            'page': page,
            'size': 24,
            'totalElements': 3,
            'totalPages': 2,
            'hasNext': page == 0,
            'departements': [
              {'code': '69', 'dealers': 2},
              {'code': '2A', 'dealers': 1},
            ],
          });
        },
      },
    );
  });

  group('directory', () {
    testWidgets('dealers with their counts; a search by département; more pages on demand', (tester) async {
      useTallScreen(tester);
      await pumpApp(tester, http, location: '/professionnels');
      await tester.pumpAndSettle();

      expect(find.text('Annuaire des professionnels'), findsOneWidget);
      expect(find.text('3 professionnels'), findsOneWidget);
      expect(find.text('12 annonces · 3 offres de location'), findsOneWidget);
      expect(find.text('Membre depuis mars 2019'), findsWidgets);
      // Everything fits on the tall screen: there is nothing to scroll, so the button asks.
      await tester.tap(find.text('Voir plus de professionnels'));
      await tester.pumpAndSettle();
      expect(find.text('Le Dernier'), findsOneWidget);
      expect(find.text('1 annonce'), findsOneWidget);

      await tester.tap(find.byType(DropdownButtonFormField<String?>));
      await tester.pumpAndSettle();
      expect(find.text('2A · Corse-du-Sud (1)'), findsOneWidget);
      await tester.tap(find.text('69 · Rhône (2)').last);
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Nom'), 'bellevue');
      await tester.tap(find.widgetWithText(FilledButton, 'Rechercher'));
      await tester.pumpAndSettle();

      expect(http.to('/api/v1/dealers').last.uri.queryParameters, {
        'q': 'bellevue',
        'departement': '69',
        'sort': 'LISTINGS',
        'page': '0',
        'size': '24',
      });
    });

    testWidgets('a dealer opens their page', (tester) async {
      await pumpApp(tester, http, location: '/professionnels');
      await tester.pumpAndSettle();

      await tester.tap(find.text('Garage Bellevue'));
      await tester.pumpAndSettle();

      expect(find.text('Concession familiale depuis 1982.'), findsOneWidget);
    });
  });

  group('dealer page', () {
    testWidgets('who they are, how to reach them, and their listings', (tester) async {
      useTallScreen(tester);
      final opened = <Uri>[];
      final preferences = await testPreferences();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...fakeApiOverrides(http, preferences: preferences),
            linkOpenerProvider.overrideWithValue((uri) async {
              opened.add(uri);
              return true;
            }),
          ],
          retry: (_, _) => null,
          child: MaterialApp.router(
            routerConfig: GoRouter(
              initialLocation: '/p',
              routes: [
                GoRoute(
                  path: '/p',
                  builder: (_, _) => const DealerScreen(slug: 'garage-bellevue'),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('12 annonces en ligne · 3 offres de location'), findsOneWidget);
      expect(find.text('Lyon (69)'), findsWidgets); // the dealer, and their listing
      expect(find.text('Lundi–samedi, 9 h – 19 h'), findsOneWidget);
      expect(find.text('garage-bellevue.fr'), findsOneWidget);
      expect(find.byType(ListingRow), findsOneWidget);
      expect(http.to('/api/v1/listings').single.uri.queryParameters['sellerId'], 's1');

      await tester.tap(find.text('+33 4 78 00 00 00'));
      await tester.tap(find.text('garage-bellevue.fr'));
      expect(opened, [Uri.parse('tel:+33478000000'), Uri.parse('https://garage-bellevue.fr/')]);

      await tester.tap(find.text('Louer'));
      await tester.pumpAndSettle();
      final last = http.to('/api/v1/listings').last.uri.queryParameters;
      expect((last['sellerId'], last['offer']), ('s1', 'LEASING'));
    });

    testWidgets('a page that is gone says so', (tester) async {
      await pumpApp(tester, http, location: '/professionnels/gone');
      await tester.pumpAndSettle();

      expect(find.text('Cette page n’existe pas ou plus.'), findsOneWidget);
    });
  });

  test('wording', () {
    expect(departementLabel('33'), '33 · Gironde');
    expect(departementLabel('99'), '99');
    expect(cardListings(DealerCard.fromJson(_card('a', 'A', sale: 0))), '0 annonce');
    expect(cardListings(DealerCard.fromJson(_card('a', 'A', sale: 0, leasing: 2))), '2 offres de location');
    expect(websiteLabel('HTTPS://garage.fr/'), 'garage.fr');
    expect(phoneUri('04 78 00 00 00'), Uri.parse('tel:0478000000'));
    expect(websiteUri('javascript:alert(1)'), isNull);
  });
}
