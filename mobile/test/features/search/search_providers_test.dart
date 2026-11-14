import 'package:autox/features/search/recent_searches.dart';
import 'package:autox/features/search/search_providers.dart';
import 'package:autox/features/search/search_query.dart';
import 'package:autox_api/autox_api.dart' show FuelType, OfferType, SearchSort;
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http.dart';
import '../../support/listings.dart';

Future<ProviderContainer> _container(FakeHttp http, [Map<String, Object> stored = const {}]) async {
  final container = ProviderContainer(
    overrides: fakeApiOverrides(http, preferences: await testPreferences(stored)),
    retry: (_, _) => null,
  );
  addTearDown(container.dispose);
  return container;
}

/// Waits until the results of [query] are no longer loading, keeping the provider alive.
Future<ResultsState> _settled(ProviderContainer container, SearchQuery query) async {
  container.listen(searchResultsProvider(query), (_, _) {});
  for (var i = 0; i < 50; i++) {
    final state = container.read(searchResultsProvider(query));
    if (state.status != ResultsStatus.loading && !state.loadingMore) {
      return state;
    }
    await Future<void>.delayed(Duration.zero);
  }
  fail('still loading');
}

void main() {
  group('SearchResults', () {
    late FakeHttp http;

    setUp(() {
      http = FakeHttp(
        {
          '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
          '/api/v1/reference/makes/m-peugeot/models': const FakeResponse.json(200, peugeotModelsJson),
        },
        handlers: {
          '/api/v1/listings': (request) => switch (request.uri.queryParameters['cursor']) {
            null => FakeResponse.json(200, pageJson([listingJson(id: 'a'), listingJson(id: 'b')], nextCursor: 'c2')),
            'c2' => FakeResponse.json(200, pageJson([listingJson(id: 'c')])),
            _ => const FakeResponse.json(400, {'code': 'INVALID_CURSOR'}),
          },
        },
      );
    });

    test('loads the first page, then the next by its cursor, then stops', () async {
      final container = await _container(http);
      const query = SearchQuery(fuel: [FuelType.DIESEL]);

      var state = await _settled(container, query);
      expect(state.items.map((item) => item.id), ['a', 'b']);
      expect(state.hasMore, isTrue);

      await container.read(searchResultsProvider(query).notifier).loadMore();
      state = await _settled(container, query);
      expect(state.items.map((item) => item.id), ['a', 'b', 'c']);
      expect(state.hasMore, isFalse);

      await container.read(searchResultsProvider(query).notifier).loadMore();
      final pages = http.to('/api/v1/listings');
      expect(pages, hasLength(2), reason: 'no request past the last page');
      expect(pages[0].uri.queryParameters, {'fuelType': 'DIESEL', 'sort': 'NEWEST', 'size': '24'});
      expect(pages[1].uri.queryParameters['cursor'], 'c2');
    });

    test('asks for one next page at a time', () async {
      final container = await _container(http);
      const query = SearchQuery.empty;
      await _settled(container, query);

      final notifier = container.read(searchResultsProvider(query).notifier);
      await Future.wait([notifier.loadMore(), notifier.loadMore(), notifier.loadMore()]);

      expect(http.to('/api/v1/listings'), hasLength(2));
    });

    test('a failed next page keeps the rows and can be tried again', () async {
      final container = await _container(http);
      const query = SearchQuery.empty;
      await _settled(container, query);
      http.handlers['/api/v1/listings'] = (_) => const FakeResponse.error(DioExceptionType.connectionError);

      await container.read(searchResultsProvider(query).notifier).loadMore();
      var state = container.read(searchResultsProvider(query));
      expect(state.items, hasLength(2));
      expect(state.loadMoreFailure?.message, contains('connexion'));
      expect(state.hasMore, isTrue);

      http.handlers['/api/v1/listings'] = (_) => FakeResponse.json(200, pageJson([listingJson(id: 'c')]));
      await container.read(searchResultsProvider(query).notifier).loadMore();
      state = container.read(searchResultsProvider(query));
      expect(state.items, hasLength(3));
      expect(state.loadMoreFailure, isNull);
    });

    test('make and model slugs are sent as catalogue ids', () async {
      final container = await _container(http);
      await _settled(container, const SearchQuery(make: 'peugeot', model: '3008'));

      expect(http.to('/api/v1/listings').single.uri.queryParameters, containsPair('makeId', 'm-peugeot'));
      expect(http.to('/api/v1/listings').single.uri.queryParameters, containsPair('modelId', 'mo-3008'));
    });

    test('a make the catalogue does not have is sent as words', () async {
      final container = await _container(http);
      await _settled(container, const SearchQuery(make: 'lada', model: 'niva'));

      final sent = http.to('/api/v1/listings').single.uri.queryParameters;
      expect(sent['make'], 'lada');
      expect(sent['model'], 'niva');
      expect(sent.containsKey('makeId'), isFalse);
    });

    test('a leasing sort is not sent with a sale', () async {
      final container = await _container(http);
      await _settled(container, SearchQuery.empty.copyWith(sort: SearchSort.MONTHLY_ASC));

      expect(http.to('/api/v1/listings').single.uri.queryParameters['sort'], 'NEWEST');
    });

    test('a failed first page is an error, and retry starts again', () async {
      http.handlers.clear();
      http.routes['/api/v1/listings'] = const FakeResponse.json(503, {'status': 503});
      final container = await _container(http);
      const query = SearchQuery(offer: OfferType.LEASING);

      final failed = await _settled(container, query);
      expect(failed.status, ResultsStatus.error);
      expect(failed.failure?.retryable, isTrue);

      http.routes['/api/v1/listings'] = FakeResponse.json(200, pageJson([listingJson()]));
      container.read(searchResultsProvider(query).notifier).retry();
      final recovered = await _settled(container, query);
      expect(recovered.status, ResultsStatus.ready);
      expect(http.to('/api/v1/listings').last.uri.queryParameters['offer'], 'LEASING');
    });
  });

  group('RecentSearches', () {
    test('keeps the last three searches with a filter, newest first, once each, without the sort', () async {
      final container = await _container(FakeHttp({}));
      final recents = container.read(recentSearchesProvider.notifier);

      recents.record(SearchQuery.empty); // nothing to keep
      recents.record(const SearchQuery(make: 'peugeot'));
      recents.record(const SearchQuery(make: 'renault'));
      recents.record(const SearchQuery(make: 'peugeot', sort: SearchSort.PRICE_ASC));
      recents.record(const SearchQuery(make: 'dacia'));
      recents.record(const SearchQuery(make: 'fiat'));

      expect(container.read(recentSearchesProvider).map((query) => query.make), ['fiat', 'dacia', 'peugeot']);
      expect(container.read(recentSearchesProvider).every((query) => query.sort == SearchQuery.defaultSort), isTrue);
    });

    test('are stored as the site’s parameters, and read back through the same validation', () async {
      final container = await _container(FakeHttp({}), {
        recentSearchesKey: '[{"marque":"peugeot","carburant":"diesel,plutonium"},{"prix_max":"abc"},"junk"]',
      });

      final entries = container.read(recentSearchesProvider);

      expect(entries, [
        const SearchQuery(make: 'peugeot', fuel: [FuelType.DIESEL]),
      ]);
      container.read(recentSearchesProvider.notifier).remove(entries.single);
      expect(container.read(recentSearchesProvider), isEmpty);
    });

    test('corrupted storage is no recent searches, not a crash', () async {
      final container = await _container(FakeHttp({}), {recentSearchesKey: '{not json'});

      expect(container.read(recentSearchesProvider), isEmpty);
    });
  });
}
