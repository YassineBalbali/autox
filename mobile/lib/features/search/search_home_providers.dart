import 'package:autox_api/autox_api.dart' show ListingSearchCriteria, Make, ResultCount;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';

/// How many listings are on AutoX: the count for empty criteria, through the hand-written
/// [SearchApi].
final listingTotalProvider = FutureProvider<ResultCount>((ref) async {
  try {
    return await ref.watch(searchApiProvider).count(ListingSearchCriteria());
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// The catalogue's makes, popular ones first then alphabetical, through the generated
/// `ReferenceApi`.
final makesProvider = FutureProvider<List<Make>>((ref) async {
  try {
    final response = await ref.watch(referenceApiProvider).makes();
    final makes = [...?response.data]..sort(_popularFirst);
    return makes;
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

int _popularFirst(Make a, Make b) {
  final popular = (b.popular ?? false ? 1 : 0) - (a.popular ?? false ? 1 : 0);
  if (popular != 0) {
    return popular;
  }
  return (a.name ?? '').toLowerCase().compareTo((b.name ?? '').toLowerCase());
}
