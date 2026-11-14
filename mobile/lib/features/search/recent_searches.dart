import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'search_query.dart';

/// The device's preferences. Overridden in `main` with the instance read at start-up, and in
/// tests with a mock.
final sharedPreferencesProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('sharedPreferencesProvider is overridden in main()'),
);

/// The storage key, the web's (`autox.search.recent`), though the two never share storage.
const recentSearchesKey = 'autox.search.recent';

/// How many are kept, as on the web.
const recentLimit = 3;

/// The last few searches with a filter set, on this phone only — the web's `RecentSearches`.
///
/// Stored as the site's query parameters and read back through [parseQuery], so an entry from an
/// older version, or edited by hand, is validated like a link rather than trusted.
class RecentSearches extends Notifier<List<SearchQuery>> {
  @override
  List<SearchQuery> build() => _read();

  /// Puts [query] first. A search without filters is not worth keeping; the sort is not kept.
  void record(SearchQuery query) {
    final entry = query.copyWith(sort: SearchQuery.defaultSort);
    if (activeFilterCount(entry) == 0) {
      return;
    }
    final id = _idOf(entry);
    _write([entry, ...state.where((existing) => _idOf(existing) != id)].take(recentLimit).toList());
  }

  void remove(SearchQuery query) {
    final id = _idOf(query);
    _write(state.where((existing) => _idOf(existing) != id).toList());
  }

  List<SearchQuery> _read() {
    final raw = ref.read(sharedPreferencesProvider).getString(recentSearchesKey);
    if (raw == null) {
      return const [];
    }
    try {
      final parsed = jsonDecode(raw);
      if (parsed is! List) {
        return const [];
      }
      final seen = <String>{};
      final entries = <SearchQuery>[];
      for (final item in parsed) {
        if (item is! Map) {
          continue;
        }
        final params = {
          for (final MapEntry(:key, :value) in item.entries)
            if (key is String && value is String) key: value,
        };
        final query = parseQuery(params).copyWith(sort: SearchQuery.defaultSort);
        if (activeFilterCount(query) > 0 && seen.add(_idOf(query))) {
          entries.add(query);
        }
      }
      return List.unmodifiable(entries.take(recentLimit));
    } on FormatException {
      return const [];
    }
  }

  void _write(List<SearchQuery> entries) {
    state = List.unmodifiable(entries);
    // Not awaited: the list on screen is already right, and a failed write only costs memory.
    ref
        .read(sharedPreferencesProvider)
        .setString(recentSearchesKey, jsonEncode(entries.map(toQueryParams).toList()))
        .ignore();
  }

  static String _idOf(SearchQuery query) => jsonEncode(toQueryParams(query.copyWith(sort: SearchQuery.defaultSort)));
}

final recentSearchesProvider = NotifierProvider<RecentSearches, List<SearchQuery>>(RecentSearches.new);
