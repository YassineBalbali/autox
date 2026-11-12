import 'package:intl/intl.dart';

final NumberFormat _integer = NumberFormat.decimalPattern('fr_FR');

/// A whole number the French way, as the web prints it: "12 480", with a narrow no-break space
/// between thousands (what `Intl.NumberFormat('fr-FR')` produces in the browser too).
String formatInteger(int value) => _integer.format(value);
