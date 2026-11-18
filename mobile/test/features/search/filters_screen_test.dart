import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

/// The sheet's own list, not a text field's inner scrollable.
final _list = find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable)).first;

void main() {
  late FakeHttp http;

  setUp(() {
    http = FakeHttp(
      {
        '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
        '/api/v1/reference/makes/m-peugeot/models': const FakeResponse.json(200, peugeotModelsJson),
        '/api/v1/reference/enums': const FakeResponse.json(200, {
          'values': {
            'FuelType': ['PETROL', 'DIESEL', 'ELECTRIC', 'SOMETHING_NEWER'],
            'Gearbox': ['MANUAL', 'AUTOMATIC'],
            'BodyType': ['SUV', 'ESTATE'],
            'VehicleCondition': ['NEW', 'USED'],
            'LeasingType': ['LOA', 'LLD'],
          },
        }),
        '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson()])),
      },
      handlers: {
        // The count answers how many filters it was sent, so the button shows which draft it saw.
        '/api/v1/listings/count': (request) =>
            FakeResponse.json(200, {'count': 100 + request.uri.queryParametersAll.length, 'capped': false}),
      },
    );
  });

  Future<void> openFilters(WidgetTester tester, String location) async {
    useTallScreen(tester);
    await pumpApp(tester, http, location: location);
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel(RegExp('^Filtres')));
    await settleDebounced(tester);
  }

  /// Opens a group of filters by its card's title.
  Future<void> openGroup(WidgetTester tester, String title) async {
    await tester.scrollUntilVisible(find.text(title), 200, scrollable: _list);
    await tester.tap(find.text(title));
    await tester.pumpAndSettle();
  }

  testWidgets('offers the values the API has and the app knows, in the API’s order', (tester) async {
    await openFilters(tester, '/recherche/resultats');
    // Nothing chosen: every group closed, each saying so.
    expect(find.byType(FilterChip), findsNothing);
    expect(find.text('Toutes les marques'), findsOneWidget);
    await openGroup(tester, 'Caractéristiques');
    await openGroup(tester, 'Prix & financement');

    final fuels = tester
        .widgetList<FilterChip>(find.byType(FilterChip))
        .map((chip) => (chip.label as Text).data)
        .take(3)
        .toList();
    expect(fuels, ['Essence', 'Diesel', 'Électrique']);
    // A sale: price, market price, damage; no leasing terms.
    expect(find.text('Prix (€)'), findsOneWidget);
    expect(find.text('Bonnes affaires uniquement'), findsOneWidget);
    expect(find.text('Mensualité (€/mois)'), findsNothing);
  });

  testWidgets('a change asks for a new count once typing stops, and "Voir" applies the draft', (tester) async {
    await openFilters(tester, '/recherche/resultats');
    expect(find.text('Voir 100 annonces'), findsOneWidget);

    await openGroup(tester, 'Caractéristiques');
    await tester.tap(find.widgetWithText(FilterChip, 'Diesel'));
    await tester.pump();
    await openGroup(tester, 'Prix & financement');
    await tester.enterText(find.byKey(const ValueKey('Prix maximum en euros')), '20000');
    await settleDebounced(tester);

    final counted = http.to('/api/v1/listings/count').last.uri.queryParameters;
    expect(counted, {'priceMax': '2000000', 'currency': 'EUR', 'fuelType': 'DIESEL'});
    expect(find.text('Voir 103 annonces'), findsOneWidget);
    // The groups' summaries say what is chosen in them.
    expect(find.textContaining('Jusqu’à 20'), findsOneWidget);

    await tester.tap(find.text('Voir 103 annonces'));
    await tester.pumpAndSettle();

    expect(find.bySemanticsLabel('Filtres, 2 actifs'), findsOneWidget);
    expect(http.to('/api/v1/listings').last.uri.queryParameters['fuelType'], 'DIESEL');
  });

  testWidgets('a reversed range is put the right way round, as a link would be', (tester) async {
    await openFilters(tester, '/recherche/resultats');

    await openGroup(tester, 'État & année');
    await tester.enterText(find.byKey(const ValueKey('Année minimum')), '2022');
    await tester.enterText(find.byKey(const ValueKey('Année maximum')), '2015');
    await settleDebounced(tester);
    await tester.tap(find.textContaining('Voir '));
    await tester.pumpAndSettle();

    final sent = http.to('/api/v1/listings').last.uri.queryParameters;
    expect((sent['yearFrom'], sent['yearTo']), ('2015', '2022'));
  });

  testWidgets('leasing shows the terms instead of the price', (tester) async {
    await openFilters(tester, '/recherche/resultats?offre=location');
    await openGroup(tester, 'Prix & financement');

    expect(find.text('Mensualité (€/mois)'), findsOneWidget);
    expect(find.text('Durée (mois)'), findsOneWidget);
    expect(find.text('Type de location'), findsOneWidget);
    expect(find.text('Prix (€)'), findsNothing);
    expect(find.text('Bonnes affaires uniquement'), findsNothing);
  });

  testWidgets('damaged vehicles only switches good deals off', (tester) async {
    await openFilters(tester, '/recherche/resultats?bonnes_affaires=1');
    // Open from the start: something is chosen in it.
    expect(find.text('Bonnes affaires uniquement'), findsOneWidget);
    await openGroup(tester, 'État & année');

    await tester.scrollUntilVisible(find.text('Endommagés uniquement'), 200, scrollable: _list);
    await tester.tap(find.text('Endommagés uniquement'));
    await settleDebounced(tester);

    final toggle = tester.widget<SwitchListTile>(find.byType(SwitchListTile));
    expect(toggle.value, isFalse);
    expect(toggle.onChanged, isNull);
  });

  testWidgets('a make, then its model, from the catalogue', (tester) async {
    await openFilters(tester, '/recherche/resultats');
    await openGroup(tester, 'Marque & modèle');

    await tester.tap(find.text('Toutes les marques').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Peugeot').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Tous les modèles'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('208').last);
    await settleDebounced(tester);
    await tester.tap(find.textContaining('Voir '));
    await tester.pumpAndSettle();

    final sent = http.to('/api/v1/listings').last.uri.queryParameters;
    expect((sent['makeId'], sent['modelId']), ('m-peugeot', 'mo-208'));
  });

  testWidgets('"Effacer" clears the draft; closing drops it', (tester) async {
    await openFilters(tester, '/recherche/resultats?carburant=diesel&annee_min=2018');

    await tester.tap(find.text('Effacer'));
    await settleDebounced(tester);
    expect(tester.widget<FilterChip>(find.widgetWithText(FilterChip, 'Diesel')).selected, isFalse);

    await tester.tap(find.byTooltip('Fermer'));
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel('Filtres, 2 actifs'), findsOneWidget, reason: 'the search itself is unchanged');
  });
}
