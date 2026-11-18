import 'package:autox/core/format/numbers.dart';
import 'package:autox/core/theme/app_theme.dart';
import 'package:autox/core/widgets/surfaces.dart';
import 'package:autox/features/listing/widgets/listing_card.dart';
import 'package:autox/features/search/recent_searches.dart' show sharedPreferencesProvider;
import 'package:autox_api/autox_api.dart' show PublicListingSummary;
import 'package:flutter/semantics.dart' show SemanticsAction;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';

Future<void> _pump(WidgetTester tester, Map<String, Object?> json, {double textScale = 1}) async {
  final preferences = await testPreferences();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [sharedPreferencesProvider.overrideWithValue(preferences)],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: MediaQuery(
          data: MediaQueryData(size: const Size(360, 800), textScaler: TextScaler.linear(textScale)),
          child: Scaffold(
            body: ListView(
              padding: const EdgeInsets.all(16),
              children: [ListingCard(listing: PublicListingSummary.fromJson(json))],
            ),
          ),
        ),
      ),
    ),
  );
}

void main() {
  testWidgets('a sale: price, verdict, name, version, the facts as chips, place, seller', (tester) async {
    await _pump(tester, listingJson(priceLevel: 'GOOD_DEAL'));

    expect(find.text(formatPrice(2249000, currency: 'EUR')), findsOneWidget);
    expect(find.text('Bonne affaire'), findsOneWidget);
    expect(find.text('Peugeot 3008'), findsOneWidget);
    expect(find.text('1.5 BlueHDi 130 Allure'), findsOneWidget);
    final chips = tester.widgetList<SpecChip>(find.byType(SpecChip)).map((chip) => chip.text);
    expect(chips, ['2020', formatKm(48000), 'Diesel', 'Automatique']);
    expect(find.text('Lyon (69)'), findsOneWidget);
    expect(find.text('Pro'), findsOneWidget);
  });

  testWidgets('a new car says "Neuf" instead of its few kilometres', (tester) async {
    await _pump(tester, listingJson(soldNew: true, mileageKm: 15, year: 2026, sellerRole: 'PARTICULIER'));

    expect(find.widgetWithText(SpecChip, 'Neuf'), findsOneWidget);
    expect(find.textContaining('15 km'), findsNothing);
    expect(find.text('Particulier'), findsOneWidget);
  });

  testWidgets('a leasing offer: the monthly payment and its terms', (tester) async {
    await _pump(
      tester,
      listingJson(leasing: {'type': 'LOA', 'monthlyPayment': 28900, 'durationMonths': 37, 'mileagePerYear': 10000}),
    );

    expect(find.text('à partir de'), findsOneWidget);
    expect(find.textContaining(formatPrice(28900, currency: 'EUR')), findsOneWidget);
    expect(find.textContaining('LOA · 37 mois'), findsOneWidget);
  });

  testWidgets('a damaged car says so first; the photo count shows past one photo', (tester) async {
    await _pump(
      tester,
      listingJson(
        photoCount: 4,
        damage: {
          'types': ['BODYWORK'],
          'running': false,
        },
      ),
    );

    expect(find.text('Véhicule endommagé'), findsOneWidget);
    expect(find.text('Carrosserie · Non roulant'), findsOneWidget);
    expect(find.text('4'), findsOneWidget);
  });

  testWidgets('TalkBack reads the card as one button, with what it shows', (tester) async {
    final semantics = tester.ensureSemantics();
    await _pump(tester, listingJson(priceLevel: 'MARKET'));

    // The card's own node: the one with the tap action, its texts merged into it.
    final node = tester.getSemantics(find.text('Peugeot 3008'));
    expect(
      node.label,
      allOf(contains(formatPrice(2249000, currency: 'EUR')), contains('Peugeot 3008'), contains('Lyon (69)')),
    );
    expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
    expect(tester.getSize(find.byType(ListingCard)).height, greaterThanOrEqualTo(48));
    semantics.dispose();
  });

  testWidgets('a 360 dp phone at twice the text size: nothing overflows', (tester) async {
    await _pump(
      tester,
      listingJson(priceLevel: 'ABOVE_MARKET', version: 'E-Tech full hybrid 145 Iconic Business', photoCount: 3),
      textScale: 2,
    );

    expect(tester.takeException(), isNull);
  });
}
