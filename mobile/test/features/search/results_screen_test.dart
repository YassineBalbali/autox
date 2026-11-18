import 'package:autox/features/listing/widgets/listing_card.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

void main() {
  late FakeHttp http;

  setUp(() {
    http = FakeHttp(
      {
        '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
        '/api/v1/reference/makes/m-peugeot/models': const FakeResponse.json(200, peugeotModelsJson),
        '/api/v1/listings/count': const FakeResponse.json(200, {'count': 30, 'capped': false}),
      },
      handlers: {
        '/api/v1/listings': (request) {
          final second = request.uri.queryParameters['cursor'] == 'next';
          return FakeResponse.json(
            200,
            pageJson([
              for (var i = 0; i < (second ? 6 : 24); i++)
                listingJson(id: '${second ? 'b' : 'a'}$i', mileageKm: 10000 + i),
            ], nextCursor: second ? null : 'next'),
          );
        },
      },
    );
  });

  testWidgets('the count, the filters as chips, and the rows', (tester) async {
    await pumpApp(tester, http, location: '/recherche/resultats?marque=peugeot&carburant=diesel&prix_max=25000');
    await tester.pumpAndSettle();

    expect(find.text('30 annonces'), findsOneWidget);
    expect(find.bySemanticsLabel('Filtres, 3 actifs'), findsOneWidget);
    for (final chip in ['Peugeot', 'Diesel', 'Jusqu’à 25 000 €']) {
      expect(find.widgetWithText(InputChip, chip), findsOneWidget);
    }
    expect(find.byType(ListingCard), findsWidgets);
    final sent = http.to('/api/v1/listings').single.uri.queryParameters;
    expect(sent, {
      'makeId': 'm-peugeot',
      'priceMax': '2500000',
      'currency': 'EUR',
      'fuelType': 'DIESEL',
      'sort': 'NEWEST',
      'size': '24',
    });
  });

  testWidgets('scrolling to the end loads the next page, by its cursor, and says when it is all', (tester) async {
    await pumpApp(tester, http, location: '/recherche/resultats');
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(
      find.text('Vous avez vu toutes les annonces.'),
      600,
      maxScrolls: 80,
      scrollable: find.descendant(of: find.byType(CustomScrollView), matching: find.byType(Scrollable)).first,
    );
    await tester.pumpAndSettle();

    final pages = http.to('/api/v1/listings');
    expect(pages, hasLength(2));
    expect(pages.last.uri.queryParameters['cursor'], 'next');
  });

  testWidgets('removing a chip searches without that filter', (tester) async {
    await pumpApp(tester, http, location: '/recherche/resultats?marque=peugeot&carburant=diesel');
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Retirer le filtre Diesel'));
    await tester.pumpAndSettle();

    final last = http.to('/api/v1/listings').last.uri.queryParameters;
    expect(last.containsKey('fuelType'), isFalse);
    expect(last['makeId'], 'm-peugeot');
    expect(find.bySemanticsLabel('Filtres, 1 actif'), findsOneWidget);
  });

  testWidgets('"Louer" switches to leasing offers, dropping what only a sale means', (tester) async {
    await pumpApp(tester, http, location: '/recherche/resultats?prix_max=20000&carburant=diesel');
    await tester.pumpAndSettle();

    await tester.tap(find.text('Louer'));
    await tester.pumpAndSettle();

    final last = http.to('/api/v1/listings').last.uri.queryParameters;
    expect(last['offer'], 'LEASING');
    expect(last['fuelType'], 'DIESEL');
    expect(last.containsKey('priceMax'), isFalse);
    expect(find.text('Location'), findsOneWidget);
  });

  testWidgets('the sort menu offers the sale orders and applies one', (tester) async {
    await pumpApp(tester, http, location: '/recherche/resultats');
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Trier par : Plus récentes'));
    await tester.pumpAndSettle();
    for (final label in ['Prix croissant', 'Prix décroissant', 'Kilométrage croissant']) {
      expect(find.text(label), findsOneWidget);
    }
    await tester.tap(find.text('Prix croissant'));
    await tester.pumpAndSettle();

    expect(http.to('/api/v1/listings').last.uri.queryParameters['sort'], 'PRICE_ASC');
  });

  testWidgets('no result: says so, and offers to clear the filters', (tester) async {
    http.handlers.clear();
    http.routes['/api/v1/listings'] = FakeResponse.json(200, pageJson(const []));
    http.routes['/api/v1/listings/count'] = const FakeResponse.json(200, {'count': 0, 'capped': false});
    await pumpApp(tester, http, location: '/recherche/resultats?carburant=hydrogen');
    await tester.pumpAndSettle();

    expect(find.text('Aucune annonce ne correspond à votre recherche'), findsOneWidget);
    expect(find.text('0 annonce'), findsOneWidget);

    http.routes['/api/v1/listings'] = FakeResponse.json(200, pageJson([listingJson()]));
    await tester.tap(find.text('Effacer tous les filtres'));
    await tester.pumpAndSettle();

    expect(http.to('/api/v1/listings').last.uri.queryParameters.containsKey('fuelType'), isFalse);
    expect(find.byType(ListingCard), findsOneWidget);
  });

  testWidgets('a search is remembered once its results open', (tester) async {
    await pumpApp(tester, http, location: '/recherche/resultats?marque=peugeot');
    await tester.pumpAndSettle();

    // material_ui's back button: flutter_test's pageBack() looks for the framework's own class.
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();

    expect(find.text('Vos dernières recherches'), findsOneWidget);
  });
}
