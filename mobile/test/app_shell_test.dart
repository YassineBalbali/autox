import 'package:autox/app.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_http.dart';

void main() {
  Future<FakeHttp> pump(WidgetTester tester) async {
    final http = FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.json(200, [
        {'id': '1', 'slug': 'peugeot', 'name': 'Peugeot', 'popular': true},
      ]),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 1, 'capped': false}),
    });
    await tester.pumpWidget(
      ProviderScope(overrides: fakeApiOverrides(http), retry: (_, _) => null, child: const AutoxApp()),
    );
    await tester.pumpAndSettle();
    return http;
  }

  testWidgets('four tabs, in French, starting on Rechercher', (tester) async {
    await pump(tester);

    final bar = tester.widget<NavigationBar>(find.byType(NavigationBar));
    expect(bar.selectedIndex, 0);
    for (final label in ['Rechercher', 'Favoris', 'Messages', 'Compte']) {
      expect(find.descendant(of: find.byType(NavigationBar), matching: find.text(label)), findsOneWidget);
    }
    expect(find.text('Peugeot'), findsOneWidget);
  });

  testWidgets('each tab opens its screen, and Rechercher keeps its state', (tester) async {
    final http = await pump(tester);

    await tester.tap(find.text('Favoris'));
    await tester.pumpAndSettle();
    expect(find.text('Bientôt disponible'), findsOneWidget);
    expect(find.textContaining('sur ce téléphone'), findsOneWidget);

    await tester.tap(find.text('Compte'));
    await tester.pumpAndSettle();
    expect(find.textContaining('La connexion'), findsOneWidget);

    await tester.tap(find.text('Rechercher'));
    await tester.pumpAndSettle();
    expect(find.text('Peugeot'), findsOneWidget);
    // Kept alive, not reloaded.
    expect(http.requests.where((r) => r.uri.path == '/api/v1/reference/makes'), hasLength(1));
  });
}
