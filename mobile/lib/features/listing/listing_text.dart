import 'dart:math' as math;

import 'package:autox_api/autox_api.dart'
    show Feature, FeatureCategory, ImageResponse, LeasingType, LeasingView, PublicListingDetail;
import 'package:intl/intl.dart';

import '../../core/format/listing_text.dart';
import '../../core/format/numbers.dart';
import '../../core/labels/labels.g.dart';

/// The listing page's words and figures, ported from the web (`features/listing/listing-view.ts`,
/// `key-facts.ts`, `listing-summary.ts`, `financing-simulator.ts`, `shared/listing/financing.ts`,
/// `shared/listing/leasing-offer.ts`, `features/dealers`, `shared/format`). Every function here is
/// checked against the web's own output by `frontend/fixtures/listing.json`.

// ---- the specification ---------------------------------------------------------------------

typedef SpecRow = ({String label, String value});

/// "Caractéristiques": what the listing says, row by row, nothing for what it does not.
List<SpecRow> specificationRows(PublicListingDetail listing) {
  final rows = <SpecRow>[];
  void add(String label, String? value) {
    if (value != null && value.isNotEmpty) {
      rows.add((label: label, value: value));
    }
  }

  final vehicle = listing.vehicle;
  add('Année', vehicle?.firstRegistrationYear?.toString());
  add('Mise en circulation', _firstRegistration(vehicle?.firstRegistrationMonth, vehicle?.firstRegistrationYear));
  // Zero is a mileage — a new car — so the test is for a number.
  add(
    'Kilométrage',
    listing.mileageKm == null
        ? null
        : '${formatInteger(listing.mileageKm!)}${nbsp}km${(listing.soldNew ?? false) ? ' (véhicule neuf)' : ''}',
  );
  add('Carburant', vehicle?.fuelType == null ? null : fuelTypeLabel(vehicle!.fuelType!));
  add('Boîte de vitesses', vehicle?.gearbox == null ? null : gearboxLabel(vehicle!.gearbox!));
  add('Puissance', _power(vehicle?.powerHp, vehicle?.powerKw));
  add('Carrosserie', vehicle?.bodyType == null ? null : bodyTypeLabel(vehicle!.bodyType!));
  add('Portes', _positive(vehicle?.doors));
  add('Places', _positive(vehicle?.seats));
  add('Couleur', _colour(listing));
  // By the API's rule, not the stored condition: a private seller's car is never "Neuf".
  add('État', listing.condition == null ? null : vehicleConditionLabels[(listing.soldNew ?? false) ? 'NEW' : 'USED']);
  // Zero grams is a real figure for an electric car, and worth stating.
  add('Émissions de CO₂', listing.co2EmissionsGKm == null ? null : '${listing.co2EmissionsGKm}${nbsp}g/km');
  add('Norme antipollution', labelOf(emissionClassLabels, listing.emissionClass));
  return rows;
}

String? _positive(int? value) => value == null || value == 0 ? null : '$value';

String? _firstRegistration(int? month, int? year) {
  if (month == null || year == null || month < 1 || month > 12) {
    return null;
  }
  return DateFormat('MMMM y', 'fr_FR').format(DateTime.utc(year, month, 15));
}

String? _power(int? hp, int? kw) {
  hp = hp == 0 ? null : hp;
  kw = kw == 0 ? null : kw;
  if (hp != null && kw != null) {
    return '$hp${nbsp}ch ($kw${nbsp}kW)';
  }
  if (hp != null) {
    return '$hp${nbsp}ch';
  }
  return kw == null ? null : '$kw${nbsp}kW';
}

String? _colour(PublicListingDetail listing) {
  final family = labelOf(exteriorColorLabels, listing.exteriorColor);
  final named = listing.colorText?.trim() ?? '';
  if (family.isEmpty && named.isEmpty) {
    return null;
  }
  final base = family.isEmpty ? '' : '$family${(listing.metallicPaint ?? false) ? ' métallisé' : ''}';
  if (named.isEmpty) {
    return base;
  }
  return base.isEmpty ? named : '$base ($named)';
}

// ---- the equipment -------------------------------------------------------------------------

typedef FeatureGroup = ({FeatureCategory category, String title, List<String> items});

/// "Équipements": the listing's feature codes, in French from the catalogue, grouped by
/// category in the page's order and sorted within each.
List<FeatureGroup> groupFeatures(List<String>? codes, List<Feature> reference) {
  final wanted = {...?codes};
  final byCategory = <FeatureCategory, List<({int order, String label})>>{};
  for (final feature in reference) {
    if (feature.code == null || !wanted.contains(feature.code)) {
      continue;
    }
    final label = feature.labels?['fr-FR'] ?? feature.labels?['fr'];
    if (label == null || label.isEmpty) {
      continue;
    }
    final category = feature.category == null || feature.category == FeatureCategory.unknownDefaultOpenApi
        ? FeatureCategory.OTHER
        : feature.category!;
    (byCategory[category] ??= []).add((order: feature.displayOrder ?? 1 << 52, label: label));
  }
  return [
    // The order of the label table is the order of the page.
    for (final key in featureCategoryLabels.keys)
      if (byCategory[FeatureCategory.values.firstWhere((value) => value.value == key)] case final bucket?)
        (
          category: FeatureCategory.values.firstWhere((value) => value.value == key),
          title: featureCategoryLabels[key]!,
          items: (bucket..sort(_byOrderThenName)).map((entry) => entry.label).toList(),
        ),
  ];
}

int _byOrderThenName(({int order, String label}) a, ({int order, String label}) b) {
  final order = a.order.compareTo(b.order);
  return order != 0 ? order : _compareFr(a.label, b.label);
}

/// Close enough to `localeCompare(…, 'fr')` for equipment names: accents ignored first.
int _compareFr(String a, String b) {
  final plain = _fold(a).compareTo(_fold(b));
  return plain != 0 ? plain : a.compareTo(b);
}

String _fold(String text) => text
    .toLowerCase()
    .replaceAll(RegExp('[àâä]'), 'a')
    .replaceAll(RegExp('[éèêë]'), 'e')
    .replaceAll(RegExp('[îï]'), 'i')
    .replaceAll(RegExp('[ôö]'), 'o')
    .replaceAll(RegExp('[ùûü]'), 'u')
    .replaceAll('ç', 'c');

// ---- key facts -----------------------------------------------------------------------------

/// "03/2019", or the year alone when the month is unknown.
String? registration(int? year, int? month) {
  if (year == null) {
    return null;
  }
  if (month == null || month < 1 || month > 12) {
    return '$year';
  }
  return DateFormat('MM/y', 'fr_FR').format(DateTime(year, month));
}

// ---- the market price ----------------------------------------------------------------------

/// The years either side of the listing's that the comparison takes, as on the server.
const _yearSpan = 2;

/// What the market verdict rests on, in a sentence; null when there is no verdict.
String? marketBasis(PublicListingDetail listing, {int minorUnit = 2}) {
  final indicator = listing.priceIndicator;
  if (indicator?.level == null || indicator?.comparableCount == null || indicator?.medianPriceAmount == null) {
    return null;
  }
  final count = indicator!.comparableCount!;
  final vehicle = [
    listing.vehicle?.make,
    listing.vehicle?.model,
  ].where((part) => part != null && part.isNotEmpty).join(' ');
  final year = listing.vehicle?.firstRegistrationYear;
  final years = year == null ? '' : ' de ${year - _yearSpan} à ${year + _yearSpan}';
  final what = vehicle.isEmpty ? '$count annonces comparables' : '$count $vehicle';
  // To the euro: cents would claim a precision the adjustment does not have.
  final unit = math.pow(10, minorUnit).toInt();
  final median = formatPrice(
    (indicator.medianPriceAmount! / unit).round() * unit,
    currency: listing.currencyCode ?? 'EUR',
    minorUnit: minorUnit,
  );
  return 'Comparé à $what de même motorisation$years au kilométrage proche. Prix médian, ramené à l’âge et au '
      'kilométrage de cette voiture$nbsp: $median.';
}

// ---- financing -----------------------------------------------------------------------------

const financingMinMonths = 12;
const financingMaxMonths = 84;
const financingMaxRatePercent = 30;

/// The durations offered, by year.
const financingDurations = [12, 24, 36, 48, 60, 72, 84];

/// An example, labelled as one on the page, for the visitor to replace with the rate they are quoted.
const exampleRatePercent = 5.9;

/// Code de la consommation, art. L. 312-5, word for word.
const creditWarning =
    'Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager.';

typedef CreditSimulation = ({int monthly, int totalRepaid, int creditCost});

/// A loan's monthly payment, total and cost, in minor units — the web's `simulateCredit`. Null
/// for nothing borrowed, a negative rate or one above 30 %, or a duration outside 12–84 months.
CreditSimulation? simulateCredit({required num principal, required num annualRatePercent, required int months}) {
  if (!principal.isFinite ||
      !annualRatePercent.isFinite ||
      principal <= 0 ||
      annualRatePercent < 0 ||
      annualRatePercent > financingMaxRatePercent ||
      months < financingMinMonths ||
      months > financingMaxMonths) {
    return null;
  }
  final rate = annualRatePercent / 100 / 12;
  final exactMonthly = rate == 0 ? principal / months : (principal * rate) / (1 - math.pow(1 + rate, -months));
  // The total is the exact payment times the months, rounded once: at 0 % it is exactly the
  // amount borrowed, rather than a few cents off from rounding each payment first.
  final totalRepaid = _roundHalfUp(exactMonthly * months);
  return (
    monthly: _roundHalfUp(exactMonthly),
    totalRepaid: totalRepaid,
    creditCost: totalRepaid - _roundHalfUp(principal),
  );
}

/// JavaScript's `Math.round`, which rounds halves up; Dart's `round` rounds them away from zero.
/// The same for the positive amounts here, but stated rather than relied on.
int _roundHalfUp(num value) => (value + 0.5).floor();

/// A down payment in whole units: spaces of any kind allowed, empty as zero, digits only.
int? parseAmount(String text) {
  final compact = text.replaceAll(RegExp('[\\s  ]'), '');
  if (compact.isEmpty) {
    return 0;
  }
  return RegExp(r'^\d{1,9}$').hasMatch(compact) ? int.parse(compact) : null;
}

/// A rate as a person types it: "5,9" or "5.9", up to three decimals.
double? parseRate(String text) {
  final compact = text.trim().replaceFirst(',', '.');
  return RegExp(r'^\d{1,2}(\.\d{1,3})?$').hasMatch(compact) ? double.parse(compact) : null;
}

// ---- leasing -------------------------------------------------------------------------------

/// Article L. 312-19, as the DGCCRF's fact sheet on LOA states it.
const loaWithdrawal = 'Vous disposez d’un délai de rétractation de 14 jours à compter de l’acceptation du contrat.';

/// Service-public's description of an LLD, which is a rental rather than a credit.
const lldNote = 'Location sans option d’achat : le véhicule est restitué à la fin du contrat.';

String leasingTypeTitle(LeasingView leasing) =>
    leasing.type == LeasingType.LOA ? 'Location avec option d’achat (LOA)' : 'Location longue durée (LLD)';

/// "Puis 36 loyers mensuels TTC de": the payments after the first, which is the down payment.
String leasingMonthlyLabel(LeasingView leasing) {
  final count = (leasing.durationMonths ?? 1) - 1;
  final plural = count > 1;
  return 'Puis $count loyer${plural ? 's' : ''} mensuel${plural ? 's' : ''} TTC de';
}

String leasingTotalLabel(LeasingView leasing) =>
    leasing.type == LeasingType.LOA ? 'Montant total dû en cas d’achat' : 'Coût total de la location';

// ---- the gallery ---------------------------------------------------------------------------

typedef GalleryImage = ({String id, String thumbnail, String medium, String large, int? width, int? height});

/// The photos by position, each with the sizes the page needs, falling back to the next size
/// down; a photo with no size at all is left out.
List<GalleryImage> galleryImages(List<ImageResponse>? images) {
  final sorted = [...?images]..sort((a, b) => (a.position ?? 0).compareTo(b.position ?? 0));
  final gallery = <GalleryImage>[];
  for (final (index, image) in sorted.indexed) {
    final variants = image.variants ?? const {};
    final large = variants['FULL']?.url ?? variants['CARD']?.url ?? variants['THUMBNAIL']?.url;
    if (large == null) {
      continue;
    }
    final medium = variants['CARD']?.url ?? large;
    final sized = variants['FULL'] ?? variants['CARD'] ?? variants['THUMBNAIL'];
    gallery.add((
      id: image.id ?? 'image-$index',
      thumbnail: variants['THUMBNAIL']?.url ?? medium,
      medium: medium,
      large: large,
      width: sized?.width,
      height: sized?.height,
    ));
  }
  return gallery;
}

// ---- dates and counts ----------------------------------------------------------------------

/// "Membre depuis mars 2019", in the phone's time zone, as the web writes it in the browser's.
String? memberSince(DateTime? date) =>
    date == null ? null : 'Membre depuis ${DateFormat('MMMM y', 'fr_FR').format(date.toLocal())}';

/// "11 novembre 2026".
String? formatDate(DateTime? date) => date == null ? null : DateFormat('d MMMM y', 'fr_FR').format(date.toLocal());

/// "0 annonce", "1 annonce", "12 480 annonces": French takes the singular below two.
String countFr(int count, String singular, String plural) =>
    '${formatInteger(count)} ${count.abs() >= 2 ? plural : singular}';
