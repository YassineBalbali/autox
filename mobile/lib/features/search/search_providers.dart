import 'dart:async';

import 'package:autox_api/autox_api.dart'
    show
        CatalogueApi,
        CatalogueSuggestion,
        ListingSearchCriteria,
        Make,
        MakeMatchCount,
        PublicListingSummary,
        ResultCount;
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';
import '../../core/reference/reference_providers.dart';
import 'search_query.dart';

/// Results per page, as on the web.
const pageSize = 24;

// ---- make and model ------------------------------------------------------------------------

/// The query's make and model slugs as the API takes them: catalogue ids when the catalogue has
/// them, the words otherwise — the web's `SearchResolver.vehicle`, including its fallbacks when
/// the catalogue cannot be read.
Future<ResolvedVehicle> resolveVehicle(Ref ref, SearchQuery query) async {
  final makeSlug = query.make;
  if (makeSlug == null) {
    return const ResolvedVehicle();
  }
  final asText = ResolvedVehicle(makeText: makeSlug, modelText: query.model);
  final List<Make> makes;
  try {
    makes = await ref.read(makesProvider.future);
  } on Object {
    return asText;
  }
  final make = makes.where((candidate) => candidate.slug == makeSlug && candidate.id != null).firstOrNull;
  if (make == null) {
    return asText;
  }
  final modelSlug = query.model;
  if (modelSlug == null) {
    return ResolvedVehicle(makeId: make.id);
  }
  try {
    final models = await ref.read(modelsProvider(make.id!).future);
    final model = models.where((candidate) => candidate.slug == modelSlug && candidate.id != null).firstOrNull;
    return model == null
        ? ResolvedVehicle(makeId: make.id, modelText: modelSlug)
        : ResolvedVehicle(makeId: make.id, modelId: model.id);
  } on Object {
    return ResolvedVehicle(makeId: make.id, modelText: modelSlug);
  }
}

/// The catalogue names for a make and model slug, for chips: "Peugeot", "3008".
final vehicleNamesProvider = FutureProvider.family<VehicleNames, (String?, String?)>((ref, slugs) async {
  final (makeSlug, modelSlug) = slugs;
  if (makeSlug == null) {
    return (make: null, model: null);
  }
  try {
    final makes = await ref.watch(makesProvider.future);
    final make = makes.where((candidate) => candidate.slug == makeSlug).firstOrNull;
    if (make?.id == null || modelSlug == null) {
      return (make: make?.name, model: null);
    }
    final models = await ref.watch(modelsProvider(make!.id!).future);
    return (make: make.name, model: models.where((candidate) => candidate.slug == modelSlug).firstOrNull?.name);
  } on Object {
    return (make: null, model: null);
  }
});

/// The criteria for a query, make and model resolved.
final criteriaProvider = FutureProvider.autoDispose.family<ListingSearchCriteria, SearchQuery>(
  (ref, query) async => toCriteria(query, await resolveVehicle(ref, query)),
);

// ---- counts --------------------------------------------------------------------------------

/// How many listings a query matches, for the results' heading.
final resultCountProvider = FutureProvider.autoDispose.family<ResultCount, SearchQuery>((ref, query) async {
  try {
    final criteria = await ref.watch(criteriaProvider(query).future);
    return await ref.watch(searchApiProvider).count(criteria);
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// How long the filters wait after the last change before asking for a count.
const countDebounce = Duration(milliseconds: 300);

/// The same count for the filter sheet's draft, asked only once the person stops changing it:
/// a provider for a draft nobody is looking at any more is disposed before the delay ends.
final draftCountProvider = FutureProvider.autoDispose.family<ResultCount, SearchQuery>((ref, query) async {
  await Future<void>.delayed(countDebounce);
  if (!ref.mounted) {
    throw const CancelledFailure();
  }
  return ref.watch(resultCountProvider(query).future);
});

// ---- results -------------------------------------------------------------------------------

enum ResultsStatus { loading, ready, error }

/// The pages loaded so far for one query.
@immutable
class ResultsState {
  const ResultsState({
    this.status = ResultsStatus.loading,
    this.items = const [],
    this.nextCursor,
    this.failure,
    this.loadingMore = false,
    this.loadMoreFailure,
  });

  final ResultsStatus status;
  final List<PublicListingSummary> items;

  /// The cursor for the next page; null once the last page is in.
  final String? nextCursor;
  final ApiFailure? failure;
  final bool loadingMore;
  final ApiFailure? loadMoreFailure;

  bool get hasMore => nextCursor != null;

  ResultsState copyWith({
    ResultsStatus? status,
    List<PublicListingSummary>? items,
    String? nextCursor,
    bool clearCursor = false,
    ApiFailure? failure,
    bool? loadingMore,
    ApiFailure? loadMoreFailure,
    bool clearLoadMoreFailure = false,
  }) => ResultsState(
    status: status ?? this.status,
    items: items ?? this.items,
    nextCursor: clearCursor ? null : (nextCursor ?? this.nextCursor),
    failure: failure ?? this.failure,
    loadingMore: loadingMore ?? this.loadingMore,
    loadMoreFailure: clearLoadMoreFailure ? null : (loadMoreFailure ?? this.loadMoreFailure),
  );
}

/// The results of one query, page after page by cursor — the web's `SearchResultsStore`.
class SearchResults extends Notifier<ResultsState> {
  SearchResults(this.query, {this.browse = false});

  final SearchQuery query;

  /// A landing page's list rather than a visitor's search: not counted in the search statistics.
  final bool browse;

  @override
  ResultsState build() {
    unawaited(_firstPage());
    return const ResultsState();
  }

  Future<void> _firstPage() async {
    try {
      final criteria = await _criteria();
      final page = await ref
          .read(searchApiProvider)
          .search(criteria, sort: sortFor(query.sort, query.offer), size: pageSize, browse: browse);
      if (!ref.mounted) {
        return;
      }
      state = ResultsState(
        status: ResultsStatus.ready,
        items: List.unmodifiable(page.items ?? const <PublicListingSummary>[]),
        nextCursor: (page.hasMore ?? false) ? page.nextCursor : null,
      );
    } on Object catch (error) {
      if (ref.mounted) {
        state = ResultsState(status: ResultsStatus.error, failure: ApiFailure.from(error));
      }
    }
  }

  /// Resolved here rather than through [criteriaProvider]: a notifier that `read`s an
  /// auto-disposed provider's future can see it disposed before the future completes.
  Future<ListingSearchCriteria> _criteria() async => toCriteria(query, await resolveVehicle(ref, query));

  /// The whole search again, from the first page.
  void retry() {
    state = const ResultsState();
    unawaited(_firstPage());
  }

  /// The next page, appended. Does nothing while one is loading or when there is none.
  Future<void> loadMore() async {
    final cursor = state.nextCursor;
    if (state.status != ResultsStatus.ready || cursor == null || state.loadingMore) {
      return;
    }
    state = state.copyWith(loadingMore: true, clearLoadMoreFailure: true);
    try {
      final criteria = await _criteria();
      final page = await ref
          .read(searchApiProvider)
          .search(criteria, sort: sortFor(query.sort, query.offer), cursor: cursor, size: pageSize, browse: browse);
      if (!ref.mounted) {
        return;
      }
      final hasMore = (page.hasMore ?? false) && page.nextCursor != null;
      state = state.copyWith(
        items: List.unmodifiable([...state.items, ...?page.items]),
        nextCursor: hasMore ? page.nextCursor : null,
        clearCursor: !hasMore,
        loadingMore: false,
      );
    } on Object catch (error) {
      if (ref.mounted) {
        state = state.copyWith(loadingMore: false, loadMoreFailure: ApiFailure.from(error));
      }
    }
  }
}

final searchResultsProvider = NotifierProvider.autoDispose.family<SearchResults, ResultsState, SearchQuery>(
  SearchResults.new,
);

/// The newest listings for a landing page (/neuf), not counted as a search.
final browseResultsProvider = NotifierProvider.autoDispose.family<SearchResults, ResultsState, SearchQuery>(
  (query) => SearchResults(query, browse: true),
);

/// The catalogue makes among a query's listings, with their counts (/neuf's "Marques en stock").
final makeCountsProvider = FutureProvider.autoDispose.family<List<MakeMatchCount>, SearchQuery>((ref, query) async {
  try {
    final criteria = await ref.watch(criteriaProvider(query).future);
    return await ref.watch(searchApiProvider).makeCounts(criteria);
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

// ---- suggestions ---------------------------------------------------------------------------

/// As on the web: wait for a pause in typing, and nothing under two characters.
const suggestDebounce = Duration(milliseconds: 200);
const suggestMinLength = 2;

final catalogueApiProvider = Provider<CatalogueApi>((ref) => ref.watch(autoxApiProvider).getCatalogueApi());

/// Makes and models matching what is typed. A failure is no suggestions, never an error: the
/// text can always be searched as words.
final suggestionsProvider = FutureProvider.autoDispose.family<List<CatalogueSuggestion>, String>((ref, typed) async {
  final text = typed.trim().replaceAll(RegExp(r'\s+'), ' ');
  if (text.length < suggestMinLength) {
    return const [];
  }
  await Future<void>.delayed(suggestDebounce);
  if (!ref.mounted) {
    return const [];
  }
  try {
    final response = await ref.watch(catalogueApiProvider).suggestions(q: text);
    return response.data ?? const [];
  } on Object {
    return const [];
  }
});
