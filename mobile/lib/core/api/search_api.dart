import 'package:autox_api/autox_api.dart'
    show CursorPagePublicListingSummary, ListingSearchCriteria, MakeMatchCount, ResultCount, SearchSort;
import 'package:dio/dio.dart';

/// The three endpoints that take the search criteria, hand-written.
///
/// The OpenAPI spec declares the criteria of `GET /listings`, `/listings/count` and
/// `/listings/makes` as one query object, to be exploded into its members. The generated Dart
/// client does not explode it: it passes the object itself to dio, which would send
/// `criteria=Instance of …`. Everything else about these calls — the criteria type and the
/// response types — is still the generated code; only the query string is built here.
///
/// The encoding is the web's (the TypeScript client explodes the object): one parameter per
/// criterion, named as in the spec, a list as the same name repeated
/// (`fuelType=DIESEL&fuelType=PETROL`), and a criterion left unset is not sent at all.
class SearchApi {
  SearchApi(this._dio);

  final Dio _dio;

  /// Repeated keys for lists, explicitly: the backend binds `fuelType=A&fuelType=B`, not
  /// `fuelType=A,B` or `fuelType[]=A`.
  static final Options _get = Options(method: 'GET', listFormat: ListFormat.multi);

  /// A page of results. [cursor] is the previous page's `nextCursor`; [browse] marks a request
  /// that is not a visitor's search (a landing page), so the admin's search statistics skip it.
  Future<CursorPagePublicListingSummary> search(
    ListingSearchCriteria criteria, {
    SearchSort sort = SearchSort.NEWEST,
    String? cursor,
    int? size,
    bool browse = false,
    CancelToken? cancelToken,
  }) async {
    final response = await _dio.request<Map<String, dynamic>>(
      '/api/v1/listings',
      options: _get,
      queryParameters: {
        ...encodeCriteria(criteria),
        'sort': sort.value,
        'cursor': ?cursor,
        'size': ?size,
        if (browse) 'browse': true,
      },
      cancelToken: cancelToken,
    );
    return CursorPagePublicListingSummary.fromJson(_body(response));
  }

  /// How many listings match, capped by the server (`capped` is then true).
  Future<ResultCount> count(ListingSearchCriteria criteria, {CancelToken? cancelToken}) async {
    final response = await _dio.request<Map<String, dynamic>>(
      '/api/v1/listings/count',
      options: _get,
      queryParameters: encodeCriteria(criteria),
      cancelToken: cancelToken,
    );
    return ResultCount.fromJson(_body(response));
  }

  /// The catalogue makes among the matching listings, each with its count.
  Future<List<MakeMatchCount>> makeCounts(ListingSearchCriteria criteria, {CancelToken? cancelToken}) async {
    final response = await _dio.request<List<dynamic>>(
      '/api/v1/listings/makes',
      options: _get,
      queryParameters: encodeCriteria(criteria),
      cancelToken: cancelToken,
    );
    return (response.data ?? const <dynamic>[])
        .cast<Map<String, dynamic>>()
        .map(MakeMatchCount.fromJson)
        .toList(growable: false);
  }

  static Map<String, dynamic> _body(Response<Map<String, dynamic>> response) {
    final data = response.data;
    if (data == null) {
      throw DioException(
        requestOptions: response.requestOptions,
        response: response,
        type: DioExceptionType.badResponse,
        error: 'Empty response body',
      );
    }
    return data;
  }
}

/// The criteria as query parameters: the generated `toJson`, which already leaves out unset
/// members and writes enums by their wire names, with every value made a string or a list of
/// strings — exactly what goes on the URL, so the encoding can be tested without a request.
Map<String, Object> encodeCriteria(ListingSearchCriteria criteria) {
  final encoded = <String, Object>{};
  criteria.toJson().forEach((key, value) {
    if (value == null) {
      return;
    }
    if (value is List) {
      if (value.isEmpty) {
        return;
      }
      encoded[key] = value.map(_scalar).toList(growable: false);
    } else {
      encoded[key] = _scalar(value);
    }
  });
  return encoded;
}

String _scalar(Object? value) => switch (value) {
  final String text => text,
  final num number => number.toString(),
  final bool flag => flag.toString(),
  _ => throw ArgumentError.value(value, 'value', 'Not a scalar search criterion'),
};
