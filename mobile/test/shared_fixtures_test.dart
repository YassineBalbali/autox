import 'dart:convert';
import 'dart:io';

import 'package:autox/core/format/listing_text.dart';
import 'package:autox/core/format/numbers.dart';
import 'package:autox/features/listing/listing_text.dart' as listing_text;
import 'package:autox/features/search/search_query.dart';
import 'package:autox_api/autox_api.dart' show Feature, ImageResponse, LeasingType, LeasingView, PublicListingDetail;
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';

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

/// As the generated models read a date: a string that does not parse is no date.
DateTime? _date(Object? iso) => iso is String ? DateTime.tryParse(iso) : null;

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

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

  group('listing.json', () {
    final listing = _fixture('listing.json');
    PublicListingDetail detail(Object? json) => PublicListingDetail.fromJson(json! as Map<String, Object?>);

    for (final c in _cases(listing, 'specificationRows')) {
      test('specificationRows — ${c['name']}', () {
        expect(
          listing_text
              .specificationRows(detail(c['listing']))
              .map((row) => {'label': row.label, 'value': row.value})
              .toList(),
          c['rows'],
        );
      });
    }

    for (final c in _cases(listing, 'groupFeatures')) {
      test('groupFeatures — ${c['name']}', () {
        final reference = (c['reference']! as List<Object?>)
            .map((json) => Feature.fromJson(json! as Map<String, Object?>))
            .toList();
        final groups = listing_text.groupFeatures((c['codes']! as List<Object?>).cast<String>(), reference);
        expect(
          groups
              .map((group) => {'category': group.category.value, 'title': group.title, 'items': group.items})
              .toList(),
          c['groups'],
        );
      });
    }

    test('registration', () {
      for (final c in _cases(listing, 'registration')) {
        expect(listing_text.registration(c['year'] as int?, c['month'] as int?), c['text'], reason: '$c');
      }
    });

    for (final c in _cases(listing, 'marketBasis')) {
      test('marketBasis — ${c['name']}', () {
        expect(listing_text.marketBasis(detail(c['listing'])), c['text']);
      });
    }

    for (final c in _cases(listing, 'simulateCredit')) {
      test('simulateCredit — ${c['name']}', () {
        final result = listing_text.simulateCredit(
          principal: c['principal']! as num,
          annualRatePercent: c['annualRatePercent']! as num,
          months: c['months']! as int,
        );
        expect(
          result == null
              ? null
              : {'monthly': result.monthly, 'totalRepaid': result.totalRepaid, 'creditCost': result.creditCost},
          c['result'],
        );
      });
    }

    test('parseAmount and parseRate', () {
      for (final c in _cases(listing, 'parseAmount')) {
        expect(listing_text.parseAmount(c['text']! as String), c['value'], reason: '${c['text']}');
      }
      for (final c in _cases(listing, 'parseRate')) {
        expect(listing_text.parseRate(c['text']! as String), c['value'], reason: '${c['text']}');
      }
    });

    for (final c in _cases(listing, 'galleryImages')) {
      test('galleryImages — ${c['name']}', () {
        final images = (c['images']! as List<Object?>)
            .map((json) => ImageResponse.fromJson(json! as Map<String, Object?>))
            .toList();
        expect(
          listing_text
              .galleryImages(images)
              .map(
                (image) => {
                  'id': image.id,
                  'thumbnail': image.thumbnail,
                  'medium': image.medium,
                  'large': image.large,
                  'width': image.width,
                  'height': image.height,
                },
              )
              .toList(),
          c['gallery'],
        );
      });
    }

    test('memberSince, formatDate, countFr', () {
      for (final c in _cases(listing, 'memberSince')) {
        expect(listing_text.memberSince(_date(c['iso'])), c['text'], reason: '$c');
      }
      for (final c in _cases(listing, 'formatDate')) {
        expect(listing_text.formatDate(_date(c['iso'])), c['text'], reason: '$c');
      }
      for (final c in _cases(listing, 'countFr')) {
        expect(listing_text.countFr(c['count']! as int, c['singular']! as String, c['plural']! as String), c['text']);
      }
    });
  });
}
