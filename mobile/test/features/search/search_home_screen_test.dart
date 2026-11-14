import 'dart:convert';

import 'package:autox/features/search/search_home_screen.dart';
import 'package:autox_api/autox_api.dart' show ResultCount;
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

Map<String, FakeResponse> _routes({bool smart = false}) => {
  '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
  '/api/v1/reference/makes/m-peugeot/models': const FakeResponse.json(200, peugeotModelsJson),
  '/api/v1/listings/count': const FakeResponse.json(200, {'count': 12480, 'capped': false}),
  '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson()])),
  if (smart) '/api/v1/search/capabilities': const FakeResponse.json(200, {'interpret': true}),
};

void main() {
  testWidgets('the total, the shortcuts and the makes, popular first', (tester) async {
    useTallScreen(tester);
    await pumpApp(tester, FakeHttp(_routes()));
    expect(find.bySemanticsLabel('Chargement des marques'), findsOneWidget);
    await tester.pumpAndSettle();

    expect(find.text('Trouvez votre voiture'), findsOneWidget);
    expect(find.text('12 480 annonces'), findsOneWidget);
    for (final shortcut in ['Acheter', 'Louer', 'Neuf']) {
      expect(find.text(shortcut), findsOneWidget);
    }
    final makes = tester
        .widgetList<ListTile>(find.byType(ListTile))
        .map((tile) => (tile.title! as Text).data)
        .where((title) => title != 'Annuaire des professionnels')
        .toList();
    expect(makes, ['Peugeot', 'Alpine', 'Volkswagen']);
    expect(find.text('Annuaire des professionnels'), findsOneWidget);
    // Not offered: the capabilities answered nothing (404 here).
    expect(find.text('Recherche intelligente'), findsNothing);
  });

  testWidgets('a make opens its results, by catalogue id', (tester) async {
    useTallScreen(tester);
    final http = FakeHttp(_routes());
    await pumpApp(tester, http);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Peugeot'));
    await tester.pumpAndSettle();

    expect(find.text('Annonces'), findsOneWidget);
    expect(http.to('/api/v1/listings').single.uri.queryParameters['makeId'], 'm-peugeot');
  });

  testWidgets('a failure says so in French, and "Réessayer" tries again', (tester) async {
    useTallScreen(tester);
    final http = FakeHttp({'/api/v1/reference/makes': const FakeResponse.error(DioExceptionType.receiveTimeout)});
    await pumpApp(tester, http);
    await tester.pumpAndSettle();

    expect(find.textContaining('Le serveur met du temps à répondre'), findsOneWidget);

    http.routes = _routes();
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(find.text('Peugeot'), findsOneWidget);
  });

  testWidgets('"Recherche intelligente" turns a description into a search, and says so', (tester) async {
    final http = FakeHttp({
      ..._routes(smart: true),
      '/api/v1/search/interpret': const FakeResponse.json(200, {
        'criteria': {
          'makeId': 'm-peugeot',
          'fuelType': ['DIESEL'],
          'priceMax': 1500000,
          'currency': 'EUR',
        },
        'summary': 'Peugeot · Diesel · ≤ 15 000 €',
        'notUnderstood': ['toit ouvrant'],
      }),
    });
    await pumpApp(tester, http);
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'peugeot diesel moins de 15000 euros toit ouvrant');
    await tester.tap(find.widgetWithText(FilledButton, 'Rechercher'));
    await tester.pumpAndSettle();

    expect(jsonDecode(http.to('/api/v1/search/interpret').single.data as String), {
      'query': 'peugeot diesel moins de 15000 euros toit ouvrant',
    });
    expect(find.textContaining('Recherche intelligente : « peugeot diesel'), findsOneWidget);
    expect(find.text('Non pris en compte : toit ouvrant'), findsOneWidget);
    final sent = http.to('/api/v1/listings').single.uri.queryParameters;
    expect(sent, containsPair('makeId', 'm-peugeot'));
    expect(sent, containsPair('fuelType', 'DIESEL'));
    expect(sent, containsPair('priceMax', '1500000'));
  });

  testWidgets('an unavailable interpreter is said plainly, and the page stays', (tester) async {
    await pumpApp(
      tester,
      FakeHttp({
        ..._routes(smart: true),
        '/api/v1/search/interpret': const FakeResponse.json(503, {'status': 503, 'code': 'INTERPRETER_UNAVAILABLE'}),
      }),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'un SUV');
    await tester.tap(find.widgetWithText(FilledButton, 'Rechercher'));
    await tester.pumpAndSettle();

    expect(find.text('La recherche intelligente est indisponible pour le moment.'), findsOneWidget);
    expect(find.text('Trouvez votre voiture'), findsOneWidget);
  });

  testWidgets('recent searches are listed in words, and can be forgotten', (tester) async {
    await pumpApp(
      tester,
      FakeHttp(_routes()),
      stored: {'autox.search.recent': '[{"marque":"peugeot","modele":"3008","carburant":"diesel"}]'},
    );
    await tester.pumpAndSettle();

    expect(find.text('Vos dernières recherches'), findsOneWidget);
    expect(find.text('Peugeot · 3008 · Diesel'), findsOneWidget);

    await tester.tap(find.byTooltip('Oublier cette recherche'));
    await tester.pumpAndSettle();
    expect(find.text('Vos dernières recherches'), findsNothing);
  });

  test('totalLabel', () {
    expect(totalLabel(ResultCount(count: 0, capped: false)), 'Aucune annonce');
    expect(totalLabel(ResultCount(count: 1, capped: false)), '1 annonce');
    expect(totalLabel(ResultCount(count: 10000, capped: true)), 'Plus de 10 000 annonces');
  });
}
