import 'package:autox_api/autox_api.dart' show ImageVariantView, LeasingType, LeasingView, PublicListingSummary;

import '../labels/labels.g.dart';
import 'numbers.dart';

/// How a listing's facts are written, ported from the web's `shared/listing` helpers. Every
/// function here is checked against the web's own output by `frontend/fixtures/display.json`.

/// The model without its make repeated: ("Peugeot", "Peugeot 208") → "208".
String modelWithoutMake(String? make, String? model) {
  final name = model?.trim() ?? '';
  final prefix = '${make?.trim() ?? ''} '.toLowerCase();
  if (prefix.trim().isNotEmpty && name.toLowerCase().startsWith(prefix)) {
    return name.substring(prefix.length).trim();
  }
  return name;
}

/// "Peugeot 208".
String vehicleName(String? make, String? model) =>
    [make?.trim() ?? '', modelWithoutMake(make, model)].where((part) => part.isNotEmpty).join(' ');

/// What a result is called: the make and model, with the version under it; the seller's own
/// title when the listing has no catalogue make.
typedef Headline = ({String title, String? subtitle});

Headline headlineOf({String? title, String? make, String? model, String? version}) {
  final vehicle = vehicleName(make, model);
  if ((make?.trim() ?? '').isEmpty || vehicle.isEmpty) {
    return (title: title ?? '', subtitle: null);
  }
  final trimmed = version?.trim() ?? '';
  return (title: vehicle, subtitle: trimmed.isEmpty ? null : trimmed);
}

Headline headlineOfListing(PublicListingSummary listing) =>
    headlineOf(title: listing.title, make: listing.make, model: listing.model, version: listing.version);

/// The département for a French postal code: "75" for 75011, "974" overseas; null elsewhere.
String? departmentOf(String? postalCode, String? countryCode) {
  if (countryCode != 'FR' || postalCode == null || !RegExp(r'^\d{5}$').hasMatch(postalCode)) {
    return null;
  }
  return RegExp('^9[78]').hasMatch(postalCode) ? postalCode.substring(0, 3) : postalCode.substring(0, 2);
}

/// "Paris (75)", or the city alone, or nothing without a city.
String? placeOf({String? city, String? postalCode, String? countryCode}) {
  if (city == null || city.isEmpty) {
    return null;
  }
  final department = departmentOf(postalCode, countryCode);
  return department == null ? city : '$city ($department)';
}

/// "LOA · 37 mois · 10 000 km/an", under a leasing offer's monthly payment.
String leasingTerms(LeasingView leasing) => [
  if (leasing.type != null && leasing.type != LeasingType.unknownDefaultOpenApi) leasingTypeLabel(leasing.type!),
  '${leasing.durationMonths} mois',
  '${formatInteger(leasing.mileagePerYear ?? 0)} km/an',
].join(' · ');

/// The photo a result shows: the card size, or the thumbnail when there is no card size.
ImageVariantView? coverOf(PublicListingSummary listing) {
  final cover = listing.coverImage ?? const <String, ImageVariantView>{};
  final variant = cover['CARD'] ?? cover['THUMBNAIL'];
  return (variant?.url ?? '').isEmpty ? null : variant;
}

/// The label for a wire value, or the value itself when there is none — ugly, and therefore
/// noticed, as on the web (`labelOf`).
String labelOf(Map<String, String> labels, String? value) {
  if (value == null || value.isEmpty) {
    return '';
  }
  return labels[value] ?? value;
}
