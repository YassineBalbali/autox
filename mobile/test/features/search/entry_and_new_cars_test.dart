import 'package:autox/features/listing/widgets/listing_row.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';
import '../../support/pump_app.dart';

void main() {
  late FakeHttp http;

  setUp(() {
    http = FakeHttp({
      '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
      '/api/v1/reference/makes/m-peugeot/models': const FakeResponse.json(200, peugeotModelsJson),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 5, 'capped': false}),
      '/api/v1/listings': FakeResponse.json(200, pageJson([listingJson(soldNew: true, mileageKm: 12)])),
      '/api/v1/catalogue/suggestions': const FakeResponse.json(200, [
        {
          'type': 'MODEL',
          'makeId': 'm-peugeot',
          'makeSlug': 'peugeot',
          'makeName': 'Peugeot',
          'modelId': 'mo-3008',
          'modelSlug': '3008',
          'modelName': '3008',
          'label': 'Peugeot 3008',
          'listingCount': 12,
        },
      ]),
      '/api/v1/listings/makes': const FakeResponse.json(200, [
        {'slug': 'peugeot', 'name': 'Peugeot', 'count': 4},
        {'slug': 'renault', 'name': 'Renault', 'count': 1},
      ]),
    });
  });

  group('search entry', () {
    testWidgets('suggestions once two characters are typed and typing pauses', (tester) async {
      await pumpApp(tester, http, location: '/recherche/saisie');
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'p');
      await settleDebounced(tester);
      expect(http.to('/api/v1/catalogue/suggestions'), isEmpty);

      await tester.enterText(find.byType(TextField), 'peu');
      await settleDebounced(tester);
      expect(http.to('/api/v1/catalogue/suggestions').single.uri.queryParameters, {'q': 'peu'});
      expect(find.text('Peugeot 3008'), findsOneWidget);
      expect(find.text('12 annonces'), findsOneWidget);
    });

    testWidgets('a suggestion searches its make and model, not the words', (tester) async {
      await pumpApp(tester, http, location: '/recherche/saisie');
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'peugeot 30');
      await settleDebounced(tester);

      await tester.tap(find.text('Peugeot 3008'));
      await tester.pumpAndSettle();

      final sent = http.to('/api/v1/listings').single.uri.queryParameters;
      expect((sent['makeId'], sent['modelId'], sent['q']), ('m-peugeot', 'mo-3008', null));
    });

    testWidgets('submitting searches the words', (tester) async {
      await pumpApp(tester, http, location: '/recherche/saisie');
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'toit ouvrant');
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pumpAndSettle();

      expect(http.to('/api/v1/listings').single.uri.queryParameters['q'], 'toit ouvrant');
      expect(find.widgetWithText(InputChip, '« toit ouvrant »'), findsOneWidget);
    });
  });

  group('/neuf', () {
    testWidgets('makes with new stock and the latest arrivals, not counted as a search', (tester) async {
      await pumpApp(tester, http, location: '/recherche/neuf');
      await tester.pumpAndSettle();

      expect(find.text('Voitures neuves'), findsOneWidget);
      expect(find.text('Peugeot · 4'), findsOneWidget);
      expect(http.to('/api/v1/listings/makes').single.uri.queryParameters, {'condition': 'NEW'});
      final latest = http.to('/api/v1/listings').single.uri.queryParameters;
      expect((latest['condition'], latest['browse']), ('NEW', 'true'));
      expect(find.byType(ListingRow), findsOneWidget);
      expect(find.textContaining('Neuf'), findsWidgets);
    });

    testWidgets('a make opens the new-car search for it', (tester) async {
      await pumpApp(tester, http, location: '/recherche/neuf');
      await tester.pumpAndSettle();

      await tester.tap(find.text('Peugeot · 4'));
      await tester.pumpAndSettle();

      final sent = http.to('/api/v1/listings').last.uri.queryParameters;
      expect((sent['condition'], sent['makeId'], sent['browse']), ('NEW', 'm-peugeot', null));
    });
  });
}
