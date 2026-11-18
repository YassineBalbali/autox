import 'package:autox/core/theme/app_theme.dart';
import 'package:autox/core/theme/tokens.dart';
import 'package:autox/features/search/filters_screen.dart';
import 'package:autox/features/search/search_query.dart';
import 'package:autox_api/autox_api.dart'
    show BodyType, DamageFilter, FuelType, Gearbox, LeasingType, OfferType, VehicleCondition;
import 'package:flutter/rendering.dart' show RenderParagraph;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

/// Every filter set at once, for a sale and for leasing.
const _sale = SearchQuery(
  make: 'peugeot',
  model: '208',
  yearMin: 2018,
  priceMax: 20000,
  mileageMax: 100000,
  fuel: [FuelType.DIESEL],
  gearbox: [Gearbox.AUTOMATIC],
  body: [BodyType.SUV],
  condition: [VehicleCondition.USED],
  damage: DamageFilter.ALL,
  location: 'Lyon',
  goodDeals: true,
  critAirMax: 2,
);
const _leasing = SearchQuery(
  offer: OfferType.LEASING,
  leasingType: [LeasingType.LOA],
  monthlyMax: 400,
  durationMin: 24,
  allowanceMin: 15000,
);

/// A small phone with the system text at its largest.
void _smallPhoneLargeText(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400); // 360 × 800 dp
  tester.view.devicePixelRatio = 3;
  tester.platformDispatcher.textScaleFactorTestValue = 2;
  addTearDown(tester.view.reset);
  addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
}

void main() {
  test('the five groups hold every filter, each in one group only', () {
    final keys = {
      for (final query in [_sale, _leasing]) ...describeFilters(query).map((filter) => filter.key.split(':').first),
    };
    for (final key in keys) {
      final holders = FilterGroup.values.where((group) => group.keys.contains(key));
      expect(holders, hasLength(1), reason: '"$key" must be in exactly one group');
    }
    // And no group names a filter that does not exist.
    expect(FilterGroup.values.expand((group) => group.keys).toSet(), keys);
  });

  test('a group’s summary is what is chosen in it, in words', () {
    final applied = describeFilters(_sale, names: (make: 'Peugeot', model: '208'));

    expect(FilterGroup.vehicle.summaryOf(applied), 'Peugeot, 208, SUV / 4x4');
    expect(FilterGroup.features.summaryOf(applied), 'Diesel, Boîte automatique, Crit’Air 2 ou mieux');
    expect(FilterGroup.location.summaryOf(describeFilters(SearchQuery.empty)), isEmpty);
  });

  group('360 dp wide, text at 200 %', () {
    FakeHttp http() => FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 12480, 'capped': false}),
      '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson(priceLevel: 'GOOD_DEAL', photoCount: 3)])),
    });

    testWidgets('the search home, with a recent search', (tester) async {
      _smallPhoneLargeText(tester);
      await pumpApp(
        tester,
        http(),
        stored: {'autox.search.recent': '[{"marque":"peugeot","carburant":"diesel","prix_max":"20000"}]'},
      );
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Trouvez votre voiture'), findsOneWidget);
    });

    testWidgets('the results, with chips and a card', (tester) async {
      _smallPhoneLargeText(tester);
      await pumpApp(tester, http(), location: '/recherche/resultats?marque=peugeot&carburant=diesel&prix_max=20000');
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.bySemanticsLabel('Filtres, 3 actifs'), findsOneWidget);
    });

    testWidgets('the filters, every group open', (tester) async {
      _smallPhoneLargeText(tester);
      await pumpApp(tester, http(), location: '/recherche/resultats');
      await tester.pumpAndSettle();
      await tester.tap(find.bySemanticsLabel('Filtres'));
      await tester.pumpAndSettle();
      for (final group in FilterGroup.values) {
        await tester.scrollUntilVisible(
          find.text(group.title),
          200,
          scrollable: find.descendant(of: find.byType(FiltersScreen), matching: find.byType(Scrollable)).first,
        );
        // Brought to the top: near the bottom, the sticky button covers it.
        await Scrollable.ensureVisible(tester.element(find.text(group.title)), alignment: 0.1);
        await tester.pumpAndSettle();
        await tester.tap(find.text(group.title));
        await tester.pumpAndSettle();
      }

      expect(tester.takeException(), isNull);
    });
  });

  testWidgets('a filter chip’s label is drawn in ink, and in blue once chosen', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light(),
        home: Scaffold(
          body: Row(
            children: [
              FilterChip(label: const Text('Diesel'), selected: false, onSelected: (_) {}),
              FilterChip(
                label: const Text('Essence'),
                labelStyle: AppText.chipSelected,
                selected: true,
                onSelected: (_) {},
              ),
            ],
          ),
        ),
      ),
    );

    Color? colourOf(String label) => tester.renderObject<RenderParagraph>(find.text(label)).text.style?.color;
    expect(colourOf('Diesel'), AppColors.ink);
    expect(colourOf('Essence'), AppColors.accentStrong);
  });

  testWidgets('the tab bar: four labelled tabs, each at least 48 dp, the current one selected', (tester) async {
    final semantics = tester.ensureSemantics();
    await pumpApp(tester, FakeHttp({'/api/v1/reference/makes': const FakeResponse.json(200, makesJson)}));
    await tester.pumpAndSettle();

    for (final label in ['Rechercher', 'Favoris', 'Messages', 'Compte']) {
      final tab = find.descendant(of: find.byType(NavigationBar), matching: find.text(label));
      expect(tab, findsOneWidget);
      final size = tester.getSize(find.ancestor(of: tab, matching: find.byType(NavigationDestination)));
      expect(size.height, greaterThanOrEqualTo(48));
      expect(size.width, greaterThanOrEqualTo(48));
    }
    expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 0);
    semantics.dispose();
  });
}
