import 'package:autox_api/autox_api.dart' show AuthenticationApi, AutoxApi, ReferenceApi;
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../auth/auth_interceptor.dart';
import '../auth/session.dart';
import '../config/app_config.dart';
import 'logging_interceptor.dart';
import 'search_api.dart';

/// The build's configuration. Overridden in `main` with [AppConfig.fromEnvironment], and in
/// tests with whatever they need.
final appConfigProvider = Provider<AppConfig>(
  (ref) => throw UnimplementedError('appConfigProvider is overridden in main()'),
);

/// What carries the requests: dio's own, except in tests, which put a fake here. Both clients
/// below use it.
final httpAdapterProvider = Provider<HttpClientAdapter?>((ref) => null);

/// Where request logs go: the console, in debug builds only. Tests capture it to prove that no
/// token is ever written there.
final logSinkProvider = Provider<void Function(String line)?>((ref) => logSinkFor(debug: kDebugMode));

/// The console in a debug build; nothing at all otherwise. Without a sink no logging interceptor
/// is installed, so a profile or release build writes no request line, header or body anywhere.
void Function(String line)? logSinkFor({required bool debug}) => debug ? debugPrint : null;

BaseOptions _options(AppConfig config, {required Duration receive}) => BaseOptions(
  baseUrl: config.apiBaseUrl,
  // Long on purpose. The API runs on Render's free plan, which sleeps when idle and takes
  // 30 to 60 seconds to answer the first request after; the connection itself opens
  // quickly, the answer is what is slow.
  connectTimeout: const Duration(seconds: 20),
  receiveTimeout: receive,
  sendTimeout: const Duration(seconds: 30),
  headers: {
    'Accept': 'application/json',
    // The sessions screen lists the user agent; "Dart/3.14 (dart:io)" says nothing.
    'User-Agent': 'AutoX-Android',
  },
  listFormat: ListFormat.multi,
);

Dio _client(Ref ref, BaseOptions options) {
  final dio = Dio(options);
  final adapter = ref.watch(httpAdapterProvider);
  if (adapter != null) {
    dio.httpClientAdapter = adapter;
  }
  final sink = ref.watch(logSinkProvider);
  if (sink != null) {
    dio.interceptors.add(RedactingLogInterceptor(sink));
  }
  ref.onDispose(dio.close);
  return dio;
}

/// The client for everything but `/auth/*`: carries the access token, and renews it once on a
/// 401 ([AuthInterceptor]).
final dioProvider = Provider<Dio>((ref) {
  final dio = _client(ref, _options(ref.watch(appConfigProvider), receive: const Duration(seconds: 90)));
  // Read lazily, on each request: the session needs the auth client below, not this one.
  dio.interceptors.insert(0, AuthInterceptor(dio, () => ref.read(sessionProvider.notifier)));
  return dio;
});

/// The client for `/auth/*` — sign-in, registration, refresh, sign-out, recovery — with no auth
/// interceptor at all: the refresh call can never trigger another refresh, by construction. The
/// 90 seconds cover a cold start of the server.
final authDioProvider = Provider<Dio>(
  (ref) => _client(ref, _options(ref.watch(appConfigProvider), receive: const Duration(seconds: 90))),
);

/// The generated client, on [dioProvider]. Built with an empty interceptor list: the
/// generator's defaults are auth interceptors for schemes this API does not declare.
final autoxApiProvider = Provider<AutoxApi>((ref) => AutoxApi(dio: ref.watch(dioProvider), interceptors: const []));

/// `/auth/*`, on [authDioProvider].
final authenticationApiProvider = Provider<AuthenticationApi>(
  (ref) => AutoxApi(dio: ref.watch(authDioProvider), interceptors: const []).getAuthenticationApi(),
);

final referenceApiProvider = Provider<ReferenceApi>((ref) => ref.watch(autoxApiProvider).getReferenceApi());

final searchApiProvider = Provider<SearchApi>((ref) => SearchApi(ref.watch(dioProvider)));
