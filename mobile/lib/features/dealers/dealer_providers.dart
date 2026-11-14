import 'dart:async';

import 'package:autox_api/autox_api.dart' show DealerCard, DealerPage, DealerSort, DealersApi, DepartementCount;
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';

final dealersApiProvider = Provider<DealersApi>((ref) => ref.watch(autoxApiProvider).getDealersApi());

/// Dealers per page, as on the web.
const directoryPageSize = 24;

/// What the directory is asked for.
@immutable
class DealerFilters {
  const DealerFilters({this.name, this.city, this.departement, this.sort = DealerSort.LISTINGS});

  final String? name;
  final String? city;
  final String? departement;
  final DealerSort sort;

  bool get isFiltered => name != null || city != null || departement != null;

  @override
  bool operator ==(Object other) =>
      other is DealerFilters &&
      other.name == name &&
      other.city == city &&
      other.departement == departement &&
      other.sort == sort;

  @override
  int get hashCode => Object.hash(name, city, departement, sort);
}

@immutable
class DirectoryState {
  const DirectoryState({
    this.loading = true,
    this.items = const [],
    this.departements = const [],
    this.total,
    this.nextPage,
    this.failure,
    this.loadingMore = false,
    this.loadMoreFailure,
  });

  final bool loading;
  final List<DealerCard> items;

  /// The départements that have dealers, with how many: the filter's choices.
  final List<DepartementCount> departements;
  final int? total;
  final int? nextPage;
  final ApiFailure? failure;
  final bool loadingMore;
  final ApiFailure? loadMoreFailure;
}

/// "Annuaire des professionnels": the dealers with listings online, page after page.
class DealerDirectory extends Notifier<DirectoryState> {
  DealerDirectory(this.filters);

  final DealerFilters filters;

  @override
  DirectoryState build() {
    unawaited(_load(0));
    return const DirectoryState();
  }

  void retry() {
    state = const DirectoryState();
    unawaited(_load(0));
  }

  Future<void> loadMore() async {
    final next = state.nextPage;
    if (state.loading || state.loadingMore || next == null) {
      return;
    }
    state = DirectoryState(
      loading: false,
      items: state.items,
      departements: state.departements,
      total: state.total,
      nextPage: next,
      loadingMore: true,
    );
    await _load(next);
  }

  Future<void> _load(int page) async {
    try {
      final response = await ref
          .read(dealersApiProvider)
          .dealerDirectory(
            q: filters.name,
            city: filters.city,
            departement: filters.departement,
            sort: filters.sort,
            page: page,
            size: directoryPageSize,
          );
      if (!ref.mounted) {
        return;
      }
      final body = response.data;
      state = DirectoryState(
        loading: false,
        items: List.unmodifiable([if (page > 0) ...state.items, ...?body?.items]),
        departements: body?.departements ?? state.departements,
        total: body?.totalElements,
        nextPage: (body?.hasNext ?? false) ? page + 1 : null,
      );
    } on Object catch (error) {
      if (!ref.mounted) {
        return;
      }
      final failure = ApiFailure.from(error);
      state = page == 0
          ? DirectoryState(loading: false, failure: failure)
          : DirectoryState(
              loading: false,
              items: state.items,
              departements: state.departements,
              total: state.total,
              nextPage: page,
              loadMoreFailure: failure,
            );
    }
  }
}

final dealerDirectoryProvider = NotifierProvider.autoDispose.family<DealerDirectory, DirectoryState, DealerFilters>(
  DealerDirectory.new,
);

/// A dealer's page. 404 for a page that is hidden or does not exist.
final dealerPageProvider = FutureProvider.autoDispose.family<DealerPage, String>((ref, slug) async {
  try {
    final response = await ref.watch(dealersApiProvider).dealerPage(slug: slug);
    final page = response.data;
    if (page == null) {
      throw const ServerFailure(status: 404);
    }
    return page;
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});
