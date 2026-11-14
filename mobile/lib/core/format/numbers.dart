import 'package:intl/intl.dart';

/// A no-break space, what the web puts inside a number ("12 480") and before a unit.
const String nbsp = ' ';

/// The web prints numbers with U+00A0 where French formatting gives U+202F (a narrow no-break
/// space), which some fonts draw as nothing at all. Same here, so the two read the same.
/// `frontend/src/app/shared/format/number.ts`, `visibleSpaces`.
String visibleSpaces(String formatted) => formatted.replaceAll(' ', nbsp);

final NumberFormat _integer = NumberFormat.decimalPattern('fr_FR');

/// A whole number the French way: "12 480". Checked against the web by `frontend/fixtures/display.json`.
String formatInteger(int value) => visibleSpaces(_integer.format(value));

/// A price from minor units: "12 500 €", "12 345,67 €" — cents only when there are some, as on
/// the web (`shared/listing/price.ts`, `formatPrice`). [minorUnit] is the currency's decimal
/// places (`Country.currencyMinorUnit`), not a hard-coded 100.
String formatPrice(int amountMinor, {String currency = 'EUR', int minorUnit = 2}) {
  final scale = _pow10(minorUnit);
  final whole = amountMinor % scale == 0;
  final format = NumberFormat.currency(
    locale: 'fr_FR',
    name: currency,
    symbol: NumberFormat.simpleCurrency(locale: 'fr_FR', name: currency).currencySymbol,
    decimalDigits: whole ? 0 : minorUnit,
  );
  return visibleSpaces(format.format(amountMinor / scale));
}

/// "12 480 km": a distance with its unit, after a no-break space.
String formatKm(int km) => '${formatInteger(km)}${nbsp}km';

int _pow10(int exponent) {
  var value = 1;
  for (var i = 0; i < exponent; i++) {
    value *= 10;
  }
  return value;
}
