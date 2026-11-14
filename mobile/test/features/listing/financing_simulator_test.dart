import 'package:autox/core/theme/app_theme.dart';
import 'package:autox/features/listing/widgets/financing_simulator.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Future<void> _pump(WidgetTester tester, {int price = 1000000}) => tester.pumpWidget(
  MaterialApp(
    theme: AppTheme.light(),
    home: Scaffold(
      body: SingleChildScrollView(child: FinancingSimulator(price: price)),
    ),
  ),
);

Finder _field(String label) => find.widgetWithText(TextField, label);

void main() {
  testWidgets('starts on 48 months at the example rate, labelled as an example', (tester) async {
    await _pump(tester, price: 2249000);

    expect(find.text('48 mois'), findsOneWidget);
    expect(find.textContaining('Taux d’exemple (5,9 %), pas une offre de crédit'), findsOneWidget);
    expect(find.text('Montant emprunté'), findsOneWidget);
    expect(find.text('22 490 €'), findsOneWidget);
    expect(find.textContaining('Un crédit vous engage et doit être remboursé.'), findsOneWidget);
  });

  testWidgets('the published table: 10 000 € at 5 % over 48 months', (tester) async {
    await _pump(tester);

    await tester.enterText(_field('Taux annuel (%)'), '5');
    await tester.pump();

    expect(find.text('230,29 € /mois'), findsOneWidget);
    expect(find.text('11 054,06 €'), findsOneWidget);
    expect(find.text('1 054,06 €'), findsOneWidget);
  });

  testWidgets('a down payment lowers what is borrowed; one of the whole price leaves nothing', (tester) async {
    await _pump(tester);

    await tester.enterText(_field('Apport (€)'), '2 000');
    await tester.pump();
    expect(find.text('8 000 €'), findsOneWidget);

    await tester.enterText(_field('Apport (€)'), '10000');
    await tester.pump();
    expect(find.text('Avec cet apport, il n’y a rien à financer.'), findsOneWidget);
  });

  testWidgets('a rate that cannot be read, or above 30 %, says what to type', (tester) async {
    await _pump(tester);

    for (final rate in ['abc', '31']) {
      await tester.enterText(_field('Taux annuel (%)'), rate);
      await tester.pump();
      expect(find.text('Saisissez un taux entre 0 et 30 %, par exemple 5,9.'), findsOneWidget, reason: rate);
    }
  });

  testWidgets('another duration recomputes', (tester) async {
    await _pump(tester);
    await tester.enterText(_field('Taux annuel (%)'), '0');
    await tester.pump();

    await tester.tap(find.text('48 mois'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('12 mois').last);
    await tester.pumpAndSettle();

    expect(find.text('833,33 € /mois'), findsOneWidget);
  });
}
