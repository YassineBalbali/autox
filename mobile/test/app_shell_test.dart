import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_http.dart';
import 'support/listings.dart';
import 'support/pump_app.dart';

void main() {
  FakeHttp http() => FakeHttp({
    '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
    '/api/v1/listings/count': const FakeResponse.json(200, {'count': 1, 'capped': false}),
  });

  testWidgets('four tabs, in French, starting on Rechercher', (tester) async {
    await pumpApp(tester, http());
    await tester.pumpAndSettle();

    final bar = tester.widget<NavigationBar>(find.byType(NavigationBar));
    expect(bar.selectedIndex, 0);
    for (final label in ['Rechercher', 'Favoris', 'Messages', 'Compte']) {
      expect(find.descendant(of: find.byType(NavigationBar), matching: find.text(label)), findsOneWidget);
    }
  });

  testWidgets('each tab opens its screen, and Rechercher keeps its state', (tester) async {
    final fake = http();
    await pumpApp(tester, fake);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Favoris'));
    await tester.pumpAndSettle();
    expect(find.text('Bientôt disponible'), findsOneWidget);
    expect(find.textContaining('sur ce téléphone'), findsOneWidget);

    await tester.tap(find.text('Compte'));
    await tester.pumpAndSettle();
    expect(find.textContaining('La connexion'), findsOneWidget);

    await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Rechercher')));
    await tester.pumpAndSettle();
    expect(find.text('Peugeot'), findsOneWidget);
    // Kept alive, not reloaded.
    expect(fake.to('/api/v1/reference/makes'), hasLength(1));
  });
}
