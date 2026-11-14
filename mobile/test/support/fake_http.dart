import 'dart:convert';
import 'dart:typed_data';

import 'package:autox/core/api/api_providers.dart';
import 'package:autox/core/config/app_config.dart';
import 'package:autox/features/search/recent_searches.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:shared_preferences/shared_preferences.dart';

/// A canned answer: a status and a JSON body, or a transport error.
class FakeResponse {
  const FakeResponse.json(this.status, this.body) : error = null;

  const FakeResponse.error(DioExceptionType this.error) : status = 0, body = null;

  final int status;
  final Object? body;
  final DioExceptionType? error;
}

/// Answers requests by path, and records every request it was sent.
///
/// The real dio pipeline runs — query encoding, JSON decoding, the generated models' parsing —
/// so a test exercises what the app would send and read, minus the network.
class FakeHttp implements HttpClientAdapter {
  FakeHttp(this.routes, {Map<String, FakeResponse Function(RequestOptions request)>? handlers})
    : handlers = handlers ?? {};

  /// Path (without query) → answer. Replaced between calls to simulate a server that recovers.
  Map<String, FakeResponse> routes;

  /// Path → an answer that depends on the request (a page that depends on its cursor). Checked
  /// before [routes].
  final Map<String, FakeResponse Function(RequestOptions request)> handlers;

  final List<RequestOptions> requests = [];

  /// The requests made to [path].
  List<RequestOptions> to(String path) => requests.where((request) => request.uri.path == path).toList();

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    final answer = handlers[options.uri.path]?.call(options) ?? routes[options.uri.path];
    if (answer == null) {
      return ResponseBody.fromString('{"status":404}', 404, headers: _json);
    }
    if (answer.error != null) {
      throw DioException(requestOptions: options, type: answer.error!);
    }
    return ResponseBody.fromString(jsonEncode(answer.body), answer.status, headers: _json);
  }

  @override
  void close({bool force = false}) {}

  static const _json = {
    Headers.contentTypeHeader: ['application/json'],
  };
}

const testConfig = AppConfig(apiBaseUrl: 'http://api.test');

/// The provider overrides that put [http] under the whole app, and [preferences] (empty
/// in-memory ones by default; see [testPreferences]) under what it stores on the phone.
List<Override> fakeApiOverrides(FakeHttp http, {required SharedPreferences preferences}) => [
  appConfigProvider.overrideWithValue(testConfig),
  sharedPreferencesProvider.overrideWithValue(preferences),
  dioProvider.overrideWith((ref) {
    final dio = Dio(BaseOptions(baseUrl: testConfig.apiBaseUrl, listFormat: ListFormat.multi))
      ..httpClientAdapter = http;
    return dio;
  }),
];

/// In-memory preferences holding [values], as the plugin's own test double provides them.
Future<SharedPreferences> testPreferences([Map<String, Object> values = const {}]) async {
  SharedPreferences.setMockInitialValues(values);
  return SharedPreferences.getInstance();
}
