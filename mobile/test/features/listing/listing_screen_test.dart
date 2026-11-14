import 'package:autox/features/listing/widgets/financing_simulator.dart';
import 'package:autox/features/listing/widgets/listing_row.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listing_detail.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

  FakeHttp http(Map<String, Object?> detail, {List<Map<String, Object?>> similar = const []}) => FakeHttp({
    '/api/v1/listings/l1': FakeResponse.json(200, detail),
    '/api/v1/listings/l1/similar': FakeResponse.json(200, similar),
    '/api/v1/reference/features': const FakeResponse.json(200, featuresJson),
    '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
  });

  testWidgets('a sale: every section, in order, in French', (tester) async {
    useTallScreen(tester);
    await pumpApp(tester, http(detailJson()), location: '/annonces/l1');
    await tester.pumpAndSettle();

    expect(find.text('Peugeot 3008 1.5 BlueHDi 130 Allure'), findsOneWidget);
    expect(find.text('Publiée le 11 novembre 2026'), findsOneWidget);
    // The price, and the simulator's amount borrowed before any down payment.
    expect(find.text('22 490 €'), findsNWidgets(2));
    expect(find.bySemanticsLabel('Bonne affaire'), findsOneWidget);
    expect(find.textContaining('Comparé à 14 Peugeot 3008 de même motorisation de 2018 à 2022'), findsOneWidget);
    expect(find.text('Prix négociable'), findsOneWidget);
    // Key facts.
    expect(find.bySemanticsLabel('Année : 03/2020'), findsOneWidget);
    expect(find.bySemanticsLabel('Kilométrage : 48 000 km'), findsOneWidget);
    // Crit'Air, description, specification, equipment.
    expect(find.text('Crit’Air 2'), findsOneWidget);
    expect(find.text('Première main, carnet d’entretien complet.'), findsOneWidget);
    expect(find.text('Gris'), findsOneWidget);
    expect(find.text('Sécurité'), findsOneWidget);
    expect(find.text('GPS'), findsOneWidget);
    // Financing: a car for sale, not damaged.
    expect(find.text('Simuler un financement'), findsOneWidget);
    expect(find.byType(FinancingSimulator), findsOneWidget);
    // The seller, further down.
    await tester.scrollUntilVisible(
      find.text('Membre depuis mars 2019'),
      400,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    expect(find.text('Garage Bellevue'), findsOneWidget);
    expect(find.text('Lyon (69)'), findsOneWidget);
    expect(find.text('Membre depuis mars 2019'), findsOneWidget);
  });

  testWidgets('a leasing offer: its terms and mentions, and no financing', (tester) async {
    useTallScreen(tester);
    await pumpApp(
      tester,
      http(
        detailJson(
          priceIndicator: null,
          leasing: {
            'type': 'LOA',
            'monthlyPayment': 28900,
            'firstPayment': 300000,
            'durationMonths': 37,
            'mileagePerYear': 10000,
            'optionPrice': 1200000,
            'totalCost': 2540400,
          },
        ),
      ),
      location: '/annonces/l1',
    );
    await tester.pumpAndSettle();

    expect(find.text('Location avec option d’achat (LOA)'), findsOneWidget);
    expect(find.text('Puis 36 loyers mensuels TTC de'), findsOneWidget);
    expect(find.text('289 €'), findsOneWidget);
    expect(find.text('Option d’achat finale'), findsOneWidget);
    expect(find.text('Montant total dû en cas d’achat'), findsOneWidget);
    expect(find.textContaining('délai de rétractation de 14 jours'), findsOneWidget);
    expect(find.text('Simuler un financement'), findsNothing);
  });

  testWidgets('a damaged car: the notice with its description, and no financing', (tester) async {
    useTallScreen(tester);
    await pumpApp(
      tester,
      http(
        detailJson(
          damage: {
            'types': ['BODYWORK'],
            'running': false,
            'description': 'Choc avant droit.',
          },
        ),
      ),
      location: '/annonces/l1',
    );
    await tester.pumpAndSettle();

    expect(find.text('Véhicule endommagé'), findsOneWidget);
    expect(find.text('Carrosserie · Non roulant'), findsOneWidget);
    expect(find.text('Choc avant droit.'), findsOneWidget);
    expect(find.text('Simuler un financement'), findsNothing);
  });

  testWidgets('a listing no longer online says so, and leads to the listings', (tester) async {
    await pumpApp(
      tester,
      FakeHttp({
        '/api/v1/listings/gone': const FakeResponse.json(404, {'status': 404, 'code': 'NOT_FOUND'}),
        '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson()])),
        '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
      }),
      location: '/annonces/gone',
    );
    await tester.pumpAndSettle();

    expect(find.text('Cette annonce n’est plus disponible'), findsOneWidget);
    await tester.tap(find.text('Voir les annonces'));
    await tester.pumpAndSettle();
    expect(find.byType(ListingRow), findsOneWidget);
  });

  testWidgets('similar listings are shown under the page and open their own', (tester) async {
    useTallScreen(tester);
    final fake = http(
      detailJson(),
      similar: [listingJson(id: 'l2', model: '5008')],
    );
    fake.routes['/api/v1/listings/l2'] = FakeResponse.json(200, detailJson(id: 'l2'));
    fake.routes['/api/v1/listings/l2/similar'] = const FakeResponse.json(200, <Object>[]);
    await pumpApp(tester, fake, location: '/annonces/l1');
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(find.text('Annonces similaires'), 400, scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(ListingRow));
    await tester.pumpAndSettle();

    expect(fake.to('/api/v1/listings/l2'), hasLength(1));
  });

  testWidgets('a result row opens its listing', (tester) async {
    final fake = http(detailJson())
      ..routes['/api/v1/listings'] = FakeResponse.json(200, pageJson([listingJson()]))
      ..routes['/api/v1/listings/count'] = const FakeResponse.json(200, {'count': 1, 'capped': false});
    await pumpApp(tester, fake, location: '/recherche/resultats');
    await tester.pumpAndSettle();

    await tester.tap(find.byType(ListingRow));
    await tester.pumpAndSettle();

    expect(find.text('Annonce'), findsOneWidget);
    expect(fake.to('/api/v1/listings/l1'), hasLength(1));
    // Over the tab bar.
    expect(find.byType(NavigationBar), findsNothing);
  });

  testWidgets('photos: the count over them, and full screen on a tap', (tester) async {
    await pumpApp(tester, http(detailJson(images: [imageJson('b', 1), imageJson('a', 0)])), location: '/annonces/l1');
    await tester.pumpAndSettle();

    expect(find.text('1 / 2'), findsOneWidget);
    await tester.tap(find.bySemanticsLabel(RegExp('^Photo 1 sur 2')));
    await tester.pumpAndSettle();

    expect(find.byTooltip('Fermer'), findsOneWidget);
    expect(find.byType(InteractiveViewer), findsOneWidget);
  });
}
