import 'package:autox/core/api/api_providers.dart';
import 'package:autox/core/api/logging_interceptor.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http.dart';

/// A release build writes no request, header or body anywhere: there is no sink, so no logging
/// interceptor on either client.
void main() {
  test('the console in a debug build, nothing otherwise', () {
    expect(logSinkFor(debug: true), same(debugPrint));
    expect(logSinkFor(debug: false), isNull);
  });

  test('without a sink, neither client logs; with one, both do', () async {
    final preferences = await testPreferences();
    bool logs(ProviderContainer container, Provider<Dio> client) =>
        container.read(client).interceptors.any((interceptor) => interceptor is RedactingLogInterceptor);

    final release = ProviderContainer(
      overrides: [...fakeApiOverrides(FakeHttp(const {}), preferences: preferences, log: logSinkFor(debug: false))],
    );
    addTearDown(release.dispose);
    expect(logs(release, dioProvider), isFalse);
    expect(logs(release, authDioProvider), isFalse);

    final debug = ProviderContainer(
      overrides: [...fakeApiOverrides(FakeHttp(const {}), preferences: preferences, log: (_) {})],
    );
    addTearDown(debug.dispose);
    expect(logs(debug, dioProvider), isTrue);
    expect(logs(debug, authDioProvider), isTrue);
  });
}
