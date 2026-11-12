import 'package:autox/core/api/search_api.dart';
import 'package:autox_api/autox_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http.dart';

void main() {
  group('encodeCriteria', () {
    test('empty criteria send nothing', () {
      expect(encodeCriteria(ListingSearchCriteria()), isEmpty);
    });

    test('scalars by their spec names, as strings', () {
      final encoded = encodeCriteria(
        ListingSearchCriteria(
          makeId: '6f1c2a54-0000-4000-8000-000000000001',
          yearFrom: 2018,
          priceMax: 2500000,
          currency: 'EUR',
          latMin: 48.5,
          q: 'toit ouvrant',
          damage: DamageFilter.UNDAMAGED,
          offer: OfferType.SALE,
          critAirMax: 2,
        ),
      );

      expect(encoded, {
        'makeId': '6f1c2a54-0000-4000-8000-000000000001',
        'yearFrom': '2018',
        'priceMax': '2500000',
        'currency': 'EUR',
        'latMin': '48.5',
        'q': 'toit ouvrant',
        'damage': 'UNDAMAGED',
        'offer': 'SALE',
        'critAirMax': '2',
      });
    });

    test('lists keep every value, enums by their wire names', () {
      final encoded = encodeCriteria(
        ListingSearchCriteria(fuelType: [FuelType.DIESEL, FuelType.PLUGIN_HYBRID], condition: [VehicleCondition.NEW]),
      );

      expect(encoded, {
        'fuelType': ['DIESEL', 'PLUGIN_HYBRID'],
        'condition': ['NEW'],
      });
    });

    test('an empty list is not sent', () {
      expect(encodeCriteria(ListingSearchCriteria(gearbox: const [])), isEmpty);
    });
  });

  group('SearchApi on the wire', () {
    late FakeHttp http;
    late SearchApi api;

    setUp(() {
      http = FakeHttp({
        '/api/v1/listings': const FakeResponse.json(200, {
          'items': <Object>[],
          'nextCursor': 'abc',
          'hasMore': true,
          'sort': 'PRICE_ASC',
          'size': 24,
        }),
        '/api/v1/listings/count': const FakeResponse.json(200, {'count': 12480, 'capped': false}),
        '/api/v1/listings/makes': const FakeResponse.json(200, [
          {'slug': 'peugeot', 'name': 'Peugeot', 'count': 40},
        ]),
      });
      api = SearchApi(Dio(BaseOptions(baseUrl: testConfig.apiBaseUrl))..httpClientAdapter = http);
    });

    test('search repeats a list parameter and sends sort, cursor and size', () async {
      final page = await api.search(
        ListingSearchCriteria(fuelType: [FuelType.DIESEL, FuelType.PETROL], priceMax: 2000000),
        sort: SearchSort.PRICE_ASC,
        cursor: 'xyz',
        size: 24,
      );

      final uri = http.requests.single.uri;
      expect(uri.path, '/api/v1/listings');
      expect(uri.queryParametersAll, {
        'fuelType': ['DIESEL', 'PETROL'],
        'priceMax': ['2000000'],
        'sort': ['PRICE_ASC'],
        'cursor': ['xyz'],
        'size': ['24'],
      });
      // The exact string, because "repeated key" is the contract with the backend's binder.
      expect(uri.query, contains('fuelType=DIESEL&fuelType=PETROL'));
      expect(page.nextCursor, 'abc');
      expect(page.hasMore, isTrue);
    });

    test('browse is sent only when set', () async {
      await api.search(ListingSearchCriteria());
      await api.search(ListingSearchCriteria(), browse: true);

      expect(http.requests[0].uri.queryParameters.containsKey('browse'), isFalse);
      expect(http.requests[1].uri.queryParameters['browse'], 'true');
    });

    test('count and makeCounts send the criteria only', () async {
      final criteria = ListingSearchCriteria(condition: [VehicleCondition.NEW]);

      final count = await api.count(criteria);
      final makes = await api.makeCounts(criteria);

      expect(http.requests.map((r) => r.uri.toString()), [
        'http://api.test/api/v1/listings/count?condition=NEW',
        'http://api.test/api/v1/listings/makes?condition=NEW',
      ]);
      expect(count.count, 12480);
      expect(makes.single.name, 'Peugeot');
    });

    test('an enum value the app does not know yet does not break the page', () async {
      http.routes['/api/v1/listings'] = const FakeResponse.json(200, {
        'items': <Object>[],
        'hasMore': false,
        'sort': 'SOME_NEW_SORT',
      });

      final page = await api.search(ListingSearchCriteria());

      expect(page.sort, SearchSort.unknownDefaultOpenApi);
    });

    test('a field the app does not know yet is ignored', () async {
      http.routes['/api/v1/listings/count'] = const FakeResponse.json(200, {
        'count': 3,
        'capped': false,
        'addedNextYear': 'whatever',
      });

      final count = await api.count(ListingSearchCriteria());

      expect(count.count, 3);
    });
  });
}
