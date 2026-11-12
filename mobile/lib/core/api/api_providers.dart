import 'package:autox_api/autox_api.dart' show AutoxApi, ReferenceApi;
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config/app_config.dart';
import 'search_api.dart';

/// The build's configuration. Overridden in `main` with [AppConfig.fromEnvironment], and in
/// tests with whatever they need.
final appConfigProvider = Provider<AppConfig>(
  (ref) => throw UnimplementedError('appConfigProvider is overridden in main()'),
);

/// The one HTTP client, shared by the generated API classes and [SearchApi].
final dioProvider = Provider<Dio>((ref) {
  final config = ref.watch(appConfigProvider);
  final dio = Dio(
    BaseOptions(
      baseUrl: config.apiBaseUrl,
      // Long on purpose. The API runs on Render's free plan, which sleeps when idle and takes
      // 30 to 60 seconds to answer the first request after; the connection itself opens
      // quickly, the answer is what is slow.
      connectTimeout: const Duration(seconds: 20),
      receiveTimeout: const Duration(seconds: 75),
      sendTimeout: const Duration(seconds: 30),
      headers: {
        'Accept': 'application/json',
        // The sessions screen lists the user agent; "Dart/3.14 (dart:io)" says nothing.
        'User-Agent': 'AutoX-Android',
      },
      listFormat: ListFormat.multi,
    ),
  );
  ref.onDispose(dio.close);
  return dio;
});

/// The generated client, on [dioProvider]. Built with an empty interceptor list: the
/// generator's defaults are auth interceptors for schemes this API does not declare. The
/// bearer token interceptor arrives with sign-in (milestone 4).
final autoxApiProvider = Provider<AutoxApi>((ref) => AutoxApi(dio: ref.watch(dioProvider), interceptors: const []));

final referenceApiProvider = Provider<ReferenceApi>((ref) => ref.watch(autoxApiProvider).getReferenceApi());

final searchApiProvider = Provider<SearchApi>((ref) => SearchApi(ref.watch(dioProvider)));
