import 'dart:convert';
import 'dart:io';

import 'package:autox/core/format/listing_text.dart';
import 'package:autox/core/format/numbers.dart';
import 'package:autox/features/search/search_query.dart';
import 'package:autox_api/autox_api.dart' show LeasingType, LeasingView;
import 'package:flutter_test/flutter_test.dart';

/// The shared test vectors in `frontend/fixtures/`, against the app's ports of the web's rules.
///
/// The web runs the same files against its own functions (`src/app/shared/fixtures.spec.ts`),
/// and the expected values were produced by the web: when one side changes a rule without the
/// other, one of the two suites fails.
Map<String, Object?> _fixture(String name) =>
    jsonDecode(File('../frontend/fixtures/$name').readAsStringSync()) as Map<String, Object?>;

List<Map<String, Object?>> _cases(Map<String, Object?> fixture, String key) =>
    (fixture[key]! as List<Object?>).cast<Map<String, Object?>>();

SearchQuery _query(Object? partial) =>
    SearchQuery.fromJson((partial ?? const <String, Object?>{}) as Map<String, Object?>);

ResolvedVehicle _vehicle(Object? json) {
  final map = json! as Map<String, Object?>;
  return ResolvedVehicle(
    makeId: map['makeId'] as String?,
    makeText: map['makeText'] as String?,
    modelId: map['modelId'] as String?,
    modelText: map['modelText'] as String?,
  );
}

void main() {
  final search = _fixture('search-query.json');
  final display = _fixture('display.json');

  group('search-query.json', () {
    for (final c in _cases(search, 'toCriteria')) {
      test('toCriteria — ${c['name']}', () {
        final criteria = toCriteria(_query(c['query']), _vehicle(c['vehicle']));
        expect(jsonDecode(jsonEncode(criteria.toJson())), c['criteria']);
      });
    }

    for (final c in _cases(search, 'toQueryParams')) {
      test('toQueryParams — ${c['name']}', () {
        expect(toQueryParams(_query(c['query'])), c['params']);
      });
    }

    for (final c in _cases(search, 'parseQuery')) {
      test('parseQuery — ${c['name']}', () {
        final params = (c['params']! as Map<String, Object?>).cast<String, String>();
        expect(parseQuery(params), _query(c['query']));
      });
    }

    for (final c in _cases(search, 'describeFilters')) {
      test('describeFilters — ${c['name']}', () {
        final names = c['names']! as Map<String, Object?>;
        final query = _query(c['query']);
        expect(
          describeFilters(
            query,
            names: (make: names['make'] as String?, model: names['model'] as String?),
          ).map((filter) => filter.label).toList(),
          c['labels'],
        );
        expect(activeFilterCount(query), c['activeFilterCount']);
      });
    }
  });

  group('display.json', () {
    test('formatPrice', () {
      for (final c in _cases(display, 'formatPrice')) {
        expect(
          formatPrice(c['amountMinor']! as int, currency: c['currency']! as String, minorUnit: c['minorUnit']! as int),
          c['text'],
        );
      }
    });

    test('formatInteger', () {
      for (final c in _cases(display, 'formatInteger')) {
        expect(formatInteger(c['value']! as int), c['text']);
      }
    });

    test('vehicleName', () {
      for (final c in _cases(display, 'vehicleName')) {
        expect(vehicleName(c['make'] as String?, c['model'] as String?), c['text']);
      }
    });

    test('placeOf', () {
      for (final c in _cases(display, 'placeOf')) {
        expect(
          placeOf(
            city: c['city'] as String?,
            postalCode: c['postalCode'] as String?,
            countryCode: c['countryCode'] as String?,
          ),
          c['text'],
        );
      }
    });

    test('headlineOf', () {
      for (final c in _cases(display, 'headlineOf')) {
        final headline = headlineOf(
          title: c['title'] as String?,
          make: c['make'] as String?,
          model: c['model'] as String?,
          version: c['version'] as String?,
        );
        final expected = c['expected']! as Map<String, Object?>;
        expect((headline.title, headline.subtitle), (expected['title'], expected['subtitle']), reason: '$c');
      }
    });

    test('leasingTerms', () {
      for (final c in _cases(display, 'leasingTerms')) {
        final leasing = LeasingView(
          type: LeasingType.values.firstWhere((type) => type.value == c['type']),
          durationMonths: c['durationMonths'] as int?,
          mileagePerYear: c['mileagePerYear'] as int?,
        );
        expect(leasingTerms(leasing), c['text']);
      }
    });
  });
}
