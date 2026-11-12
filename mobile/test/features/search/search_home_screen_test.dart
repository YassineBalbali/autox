import 'package:autox/app.dart';
import 'package:autox/features/search/search_home_screen.dart';
import 'package:autox_api/autox_api.dart' show ResultCount;
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';

const _makes = [
  {'id': '1', 'slug': 'volkswagen', 'name': 'Volkswagen', 'popular': false},
  {'id': '2', 'slug': 'peugeot', 'name': 'Peugeot', 'popular': true},
  {'id': '3', 'slug': 'alpine', 'name': 'Alpine', 'popular': false},
];

Future<void> _pump(WidgetTester tester, FakeHttp http) async {
  await tester.pumpWidget(
    ProviderScope(overrides: fakeApiOverrides(http), retry: (_, _) => null, child: const AutoxApp()),
  );
}

void main() {
  testWidgets('shows the total and the makes, popular first', (tester) async {
    final http = FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.json(200, _makes),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 12480, 'capped': false}),
    });

    await _pump(tester, http);
    expect(find.bySemanticsLabel('Chargement des marques'), findsOneWidget);

    await tester.pumpAndSettle();

    expect(find.text('Trouvez votre voiture'), findsOneWidget);
    expect(find.text('12 480 annonces'), findsOneWidget);
    final names = tester.widgetList<ListTile>(find.byType(ListTile)).map((tile) => (tile.title! as Text).data).toList();
    expect(names, ['Peugeot', 'Alpine', 'Volkswagen']);
    expect(find.text('Populaire'), findsOneWidget);
  });

  testWidgets('a failure says so in French and "Réessayer" tries again', (tester) async {
    final http = FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.error(DioExceptionType.receiveTimeout),
      '/api/v1/listings/count': const FakeResponse.error(DioExceptionType.receiveTimeout),
    });

    await _pump(tester, http);
    await tester.pumpAndSettle();

    expect(find.textContaining('Le serveur met du temps à répondre'), findsOneWidget);
    expect(http.requests.where((r) => r.uri.path == '/api/v1/reference/makes'), hasLength(1));

    // The server has woken up.
    http.routes = {
      '/api/v1/reference/makes': const FakeResponse.json(200, _makes),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 3, 'capped': false}),
    };
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(find.text('Peugeot'), findsOneWidget);
    expect(find.text('3 annonces'), findsOneWidget);
  });

  testWidgets('a failed count leaves the makes on screen', (tester) async {
    final http = FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.json(200, _makes),
      '/api/v1/listings/count': const FakeResponse.json(503, {'status': 503}),
    });

    await _pump(tester, http);
    await tester.pumpAndSettle();

    expect(find.text('Nombre d’annonces indisponible'), findsOneWidget);
    expect(find.text('Peugeot'), findsOneWidget);
  });

  test('totalLabel', () {
    expect(totalLabel(ResultCount(count: 0, capped: false)), 'Aucune annonce');
    expect(totalLabel(ResultCount(count: 1, capped: false)), '1 annonce');
    expect(totalLabel(ResultCount(count: 999, capped: false)), '999 annonces');
    expect(totalLabel(ResultCount(count: 10000, capped: true)), 'Plus de 10 000 annonces');
  });
}
