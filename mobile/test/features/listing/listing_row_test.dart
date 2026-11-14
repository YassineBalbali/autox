import 'package:autox/core/theme/app_theme.dart';
import 'package:autox/features/listing/widgets/listing_row.dart';
import 'package:autox_api/autox_api.dart' show PublicListingSummary;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/listings.dart';

Future<void> _pump(WidgetTester tester, Map<String, Object?> json) => tester.pumpWidget(
  MaterialApp(
    theme: AppTheme.light(),
    home: Scaffold(body: ListingRow(listing: PublicListingSummary.fromJson(json))),
  ),
);

void main() {
  testWidgets('a sale: price, verdict, name and version, facts, place, seller', (tester) async {
    await _pump(tester, listingJson(priceLevel: 'GOOD_DEAL'));

    expect(find.text('22 490 €'), findsOneWidget);
    expect(find.text('Bonne affaire'), findsOneWidget);
    expect(find.textContaining('Peugeot 3008'), findsOneWidget);
    expect(find.textContaining('1.5 BlueHDi 130 Allure'), findsOneWidget);
    expect(find.text('2020 · 48 000 km'), findsOneWidget);
    expect(find.text('Diesel · Automatique'), findsOneWidget);
    expect(find.text('Lyon (69)'), findsOneWidget);
    expect(find.text('Pro'), findsOneWidget);
  });

  testWidgets('a new car says "Neuf" instead of its few kilometres', (tester) async {
    await _pump(tester, listingJson(soldNew: true, mileageKm: 15, year: 2026, sellerRole: 'PARTICULIER'));

    expect(find.text('2026 · Neuf'), findsOneWidget);
    expect(find.text('Particulier'), findsOneWidget);
  });

  testWidgets('a leasing offer: the monthly payment and its terms', (tester) async {
    await _pump(
      tester,
      listingJson(leasing: {'type': 'LOA', 'monthlyPayment': 28900, 'durationMonths': 37, 'mileagePerYear': 10000}),
    );

    expect(find.text('à partir de'), findsOneWidget);
    expect(find.textContaining('289 €'), findsOneWidget);
    expect(find.text('LOA · 37 mois · 10 000 km/an'), findsOneWidget);
  });

  testWidgets('a damaged car says so first, with what was declared', (tester) async {
    await _pump(
      tester,
      listingJson(
        damage: {
          'types': ['BODYWORK', 'HAIL'],
          'running': true,
        },
      ),
    );

    expect(find.text('Véhicule endommagé'), findsOneWidget);
    expect(find.text('Carrosserie, Grêle · Roulant'), findsOneWidget);
  });

  test('the image cache key ignores the signature in the query string', () {
    expect(
      imageCacheKey('https://bucket.example/listings/1/2/card.jpg?X-Amz-Signature=abc&X-Amz-Date=1'),
      'https://bucket.example/listings/1/2/card.jpg',
    );
    expect(imageCacheKey('https://cdn.example/a.jpg'), 'https://cdn.example/a.jpg');
  });
}
