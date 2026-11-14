import 'package:autox_api/autox_api.dart'
    show Country, Feature, ListingsPublicApi, PublicListingDetail, PublicListingSummary;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';

final listingsPublicApiProvider = Provider<ListingsPublicApi>(
  (ref) => ref.watch(autoxApiProvider).getListingsPublicApi(),
);

/// A listing's page. A listing that is no longer public answers 404: [ServerFailure] with
/// status 404, which the page words as "plus disponible".
final listingDetailProvider = FutureProvider.autoDispose.family<PublicListingDetail, String>((ref, id) async {
  try {
    final response = await ref.watch(listingsPublicApiProvider).detail(id: id);
    final listing = response.data;
    if (listing == null) {
      throw const ServerFailure(status: 404);
    }
    return listing;
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// "Annonces similaires": three to six, or none. A failure is none, not an error: it is a
/// suggestion under the page, never what the person came for.
final similarListingsProvider = FutureProvider.autoDispose.family<List<PublicListingSummary>, String>((ref, id) async {
  try {
    final response = await ref.watch(listingsPublicApiProvider).similar(id: id);
    return response.data ?? const [];
  } on Object {
    return const [];
  }
});

/// The equipment catalogue, with its French names. Kept: it changes with a deployment.
final featuresProvider = FutureProvider<List<Feature>>((ref) async {
  try {
    final response = await ref.watch(referenceApiProvider).features();
    return response.data ?? const [];
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// Decimal places by currency (`Country.currencyMinorUnit`); 2 for anything unknown, as the web.
final minorUnitsProvider = FutureProvider<Map<String, int>>((ref) async {
  try {
    final response = await ref.watch(referenceApiProvider).countries();
    return {
      for (final country in response.data ?? const <Country>[])
        if (country.currencyCode != null && country.currencyMinorUnit != null)
          country.currencyCode!: country.currencyMinorUnit!,
    };
  } on Object {
    return const {};
  }
});

/// The decimal places of [currency], once the countries are in; 2 until then.
int minorUnitOf(WidgetRef ref, String? currency) =>
    ref.watch(minorUnitsProvider).value?[currency ?? 'EUR'] ?? defaultMinorUnit;

const defaultMinorUnit = 2;
