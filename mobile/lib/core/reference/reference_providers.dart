import 'package:autox_api/autox_api.dart' show Make, Model, NaturalLanguageSearchApi;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_failure.dart';
import '../api/api_providers.dart';

/// The catalogue and the API's vocabularies: read once per launch and kept, as the web's
/// `ReferenceStore` keeps them for a session. They change with a deployment, not with a search.

/// Every catalogue make, in the API's order.
final makesProvider = FutureProvider<List<Make>>((ref) async {
  try {
    final response = await ref.watch(referenceApiProvider).makes();
    return List.unmodifiable(response.data ?? const <Make>[]);
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// A make's models, by the make's catalogue id.
final modelsProvider = FutureProvider.family<List<Model>, String>((ref, makeId) async {
  try {
    final response = await ref.watch(referenceApiProvider).models(makeId: makeId);
    return List.unmodifiable(response.data ?? const <Model>[]);
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// The values the API accepts for each enum, keyed as it documents them (`FuelType`, …). The
/// filters offer these, in this order, so a value is offered only when the server has it.
final enumValuesProvider = FutureProvider<Map<String, List<String>>>((ref) async {
  try {
    final response = await ref.watch(referenceApiProvider).enums();
    return response.data?.values ?? const {};
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

final naturalLanguageApiProvider = Provider<NaturalLanguageSearchApi>(
  (ref) => ref.watch(autoxApiProvider).getNaturalLanguageSearchApi(),
);

/// Whether the natural-language search is switched on. Not knowing counts as no: the box is
/// then simply not offered, and the ordinary search is always there.
final smartSearchAvailableProvider = FutureProvider<bool>((ref) async {
  try {
    final response = await ref.watch(naturalLanguageApiProvider).capabilities();
    return response.data?.interpret ?? false;
  } on Object {
    return false;
  }
});

/// Popular makes first, then alphabetical: the order of the home page's list.
List<Make> popularFirst(List<Make> makes) => [...makes]
  ..sort((a, b) {
    final popular = ((b.popular ?? false) ? 1 : 0) - ((a.popular ?? false) ? 1 : 0);
    if (popular != 0) {
      return popular;
    }
    return (a.name ?? '').toLowerCase().compareTo((b.name ?? '').toLowerCase());
  });
