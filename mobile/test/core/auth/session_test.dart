import 'dart:async';
import 'dart:convert';

import 'package:autox/core/api/api_failure.dart';
import 'package:autox/core/api/api_providers.dart';
import 'package:autox/core/auth/session.dart';
import 'package:autox/core/auth/token_store.dart';
import 'package:autox_api/autox_api.dart' show ErrorCode;
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http.dart';

/// A server that issues numbered token pairs and accepts only the latest access token.
class FakeAuthServer {
  int issued = 0;
  String? validAccess;
  final List<String> refreshBodies = [];

  /// Holds the refresh answer until released, so a test can pile requests up behind it.
  Completer<void>? gate;

  /// When set, the refresh endpoint answers this instead of a new pair.
  FakeResponse? refreshAnswer;

  Map<String, Object?> pair() {
    issued++;
    validAccess = 'access-$issued';
    return {
      'accessToken': 'access-$issued',
      'tokenType': 'Bearer',
      'expiresInSeconds': 900,
      'refreshToken': 'refresh-$issued',
      'user': {'id': 'u1', 'email': 'jean@exemple.fr', 'displayName': 'Jean', 'role': 'PARTICULIER'},
    };
  }

  FakeHttp http() => FakeHttp(
    {'/api/v1/auth/logout': const FakeResponse.json(204, null)},
    handlers: {
      '/api/v1/auth/refresh': (request) async {
        refreshBodies.add((jsonDecode(request.data as String) as Map<String, Object?>)['refreshToken']! as String);
        await gate?.future;
        return refreshAnswer ?? FakeResponse.json(200, pair());
      },
      '/api/v1/auth/login': (_) => FakeResponse.json(200, pair()),
      '/api/v1/me': (request) => request.headers['Authorization'] == 'Bearer $validAccess'
          ? const FakeResponse.json(200, {'id': 'u1', 'email': 'jean@exemple.fr'})
          : const FakeResponse.json(401, {'status': 401, 'code': 'INVALID_TOKEN'}),
      '/api/v1/always-401': (_) => const FakeResponse.json(401, {'status': 401, 'code': 'INVALID_TOKEN'}),
    },
  );
}

Future<ProviderContainer> _container(FakeHttp http, TokenStore tokens, {void Function(String)? log}) async {
  final container = ProviderContainer(
    overrides: fakeApiOverrides(http, preferences: await testPreferences(), tokens: tokens, log: log),
    retry: (_, _) => null,
  );
  addTearDown(container.dispose);
  return container;
}

/// Reads the session and waits for the launch restore to settle.
Future<AuthState> _started(ProviderContainer container) async {
  container.listen(sessionProvider, (_, _) {});
  for (var i = 0; i < 100 && container.read(sessionProvider) is AuthUnknown; i++) {
    await Future<void>.delayed(Duration.zero);
  }
  return container.read(sessionProvider);
}

void main() {
  late FakeAuthServer server;
  late FakeHttp http;

  setUp(() {
    server = FakeAuthServer();
    http = server.http();
  });

  test('at launch, a stored refresh token restores the session with one refresh', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);

    expect(container.read(sessionProvider), isA<AuthUnknown>());
    final state = await _started(container);

    expect(state, isA<SignedIn>());
    expect(server.refreshBodies, ['refresh-stored']);
    expect(tokens.value, 'refresh-1');
    expect(container.read(sessionProvider.notifier).accessToken, 'access-1');
  });

  test('no stored token: signed out, nothing sent', () async {
    final container = await _container(http, MemoryTokenStore());

    expect(await _started(container), isA<SignedOut>());
    expect(http.requests, isEmpty);
  });

  test('N requests failing together with 401 make exactly one refresh, and are all retried with its token', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);
    await _started(container); // access-1
    final dio = container.read(dioProvider);

    // The access token expires on the server; the next refresh is held open.
    server.validAccess = 'expired';
    server.gate = Completer<void>();
    const n = 6;
    final calls = [for (var i = 0; i < n; i++) dio.get<Object>('/api/v1/me')];
    // Until the refresh has started, then long enough for all six 401s to have arrived behind it.
    for (var i = 0; i < 500 && server.refreshBodies.length < 2; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(http.to('/api/v1/me'), hasLength(n), reason: 'all six sent, none retried yet');

    expect(server.refreshBodies, ['refresh-stored', 'refresh-1'], reason: 'one refresh in flight for all six');
    server.gate!.complete();
    final responses = await Future.wait(calls);

    expect(responses.map((response) => response.statusCode), everyElement(200));
    expect(server.refreshBodies, ['refresh-stored', 'refresh-1']);
    final retried = http.to('/api/v1/me').where((r) => r.headers['Authorization'] == 'Bearer access-2');
    expect(retried, hasLength(n));
    // No refresh token was ever presented twice.
    expect(server.refreshBodies.toSet(), hasLength(server.refreshBodies.length));
  });

  test('the rotated refresh token is stored before any waiting request resumes', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);
    await _started(container);
    final dio = container.read(dioProvider);
    server.validAccess = 'expired';

    var retriesWhenStored = -1;
    tokens.onWrite = (_) =>
        retriesWhenStored = http.to('/api/v1/me').where((r) => r.headers['Authorization'] == 'Bearer access-2').length;
    await Future.wait([dio.get<Object>('/api/v1/me'), dio.get<Object>('/api/v1/me')]);

    expect(retriesWhenStored, 0, reason: 'nothing retried before the new refresh token was written');
    expect(tokens.writes.last, 'refresh-2');
  });

  test('a refresh the server refuses clears the stored token and signs out, saying so', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);
    await _started(container);
    server.validAccess = 'expired';
    server.refreshAnswer = const FakeResponse.json(401, {'status': 401, 'code': 'INVALID_REFRESH_TOKEN'});

    final error = await container
        .read(dioProvider)
        .get<Object>('/api/v1/me')
        .then<Object?>((_) => null, onError: (Object e) => e);

    expect(ApiFailure.from(error!), isA<ServerFailure>());
    expect(tokens.value, isNull);
    expect(container.read(sessionProvider), isA<SignedOut>().having((s) => s.expired, 'expired', isTrue));
    expect(container.read(sessionProvider.notifier).accessToken, isNull);
  });

  test('an account suspended since (403 on refresh) is signed out too, as refused, not as expired', () async {
    server.refreshAnswer = const FakeResponse.json(403, {'status': 403, 'code': 'ACCOUNT_SUSPENDED'});
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);

    expect(
      await _started(container),
      isA<SignedOut>()
          .having((s) => s.expired, 'expired', isFalse)
          .having((s) => s.refused?.code, 'refused', ErrorCode.ACCOUNT_SUSPENDED),
    );
    expect(tokens.value, isNull);

    container.read(sessionProvider.notifier).acknowledgeExpiry();
    expect(container.read(sessionProvider), isA<SignedOut>().having((s) => s.refused, 'refused', isNull));
  });

  test('a refresh that cannot reach the server keeps the token, and the next request tries again', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    server.refreshAnswer = const FakeResponse.error(DioExceptionType.connectionError);
    final container = await _container(http, tokens);

    expect(await _started(container), isA<SessionUnavailable>());
    expect(tokens.value, 'refresh-stored');

    // Back online: a request needing the session refreshes and succeeds.
    server.refreshAnswer = null;
    final response = await container.read(dioProvider).get<Object>('/api/v1/me');
    expect(response.statusCode, 200);
    expect(container.read(sessionProvider), isA<SignedIn>());
    expect(server.refreshBodies, ['refresh-stored', 'refresh-stored']);
  });

  test('a network failure while signed in surfaces as offline and keeps the session', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);
    await _started(container);
    server.validAccess = 'expired';
    server.refreshAnswer = const FakeResponse.error(DioExceptionType.connectionError);

    final error = await container
        .read(dioProvider)
        .get<Object>('/api/v1/me')
        .then<Object?>((_) => null, onError: (Object e) => e);

    expect(ApiFailure.from(error!), isA<OfflineFailure>());
    expect(tokens.value, 'refresh-1');
    expect(container.read(sessionProvider), isA<SignedIn>());
  });

  test('a request that answers 401 again after its retry is not retried a second time', () async {
    final container = await _container(http, MemoryTokenStore('refresh-stored'));
    await _started(container);

    final error = await container
        .read(dioProvider)
        .get<Object>('/api/v1/always-401')
        .then<Object?>((_) => null, onError: (Object e) => e);

    expect((error! as DioException).response?.statusCode, 401);
    expect(http.to('/api/v1/always-401'), hasLength(2));
    expect(server.refreshBodies, hasLength(2), reason: 'the restore, and the one renewal');
  });

  test('the refresh call never goes through the auth interceptor', () async {
    final container = await _container(http, MemoryTokenStore('refresh-stored'));
    await _started(container);

    final authDio = container.read(authDioProvider);
    final refreshCalls = http.to('/api/v1/auth/refresh');

    expect(
      authDio.interceptors.whereType<Interceptor>().map((i) => i.runtimeType.toString()),
      isNot(contains('AuthInterceptor')),
    );
    expect(refreshCalls.single.headers.containsKey('Authorization'), isFalse);
  });

  test('signing out clears the phone even when the server cannot be reached', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);
    await _started(container);
    http.routes['/api/v1/auth/logout'] = const FakeResponse.error(DioExceptionType.connectionError);

    await container.read(sessionProvider.notifier).logout();

    expect(tokens.value, isNull);
    expect(container.read(sessionProvider), isA<SignedOut>().having((s) => s.expired, 'expired', isFalse));
    expect(container.read(sessionProvider.notifier).accessToken, isNull);
    expect(http.to('/api/v1/auth/logout').single.data, contains('refresh-1'));
  });

  test('signing out waits for a refresh in flight, then revokes the newest token', () async {
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens);
    await _started(container);
    server.gate = Completer<void>();
    final refreshing = container.read(sessionProvider.notifier).refreshAccessToken();

    final loggingOut = container.read(sessionProvider.notifier).logout();
    server.gate!.complete();
    await Future.wait([refreshing, loggingOut]);

    expect(http.to('/api/v1/auth/logout').single.data, contains('refresh-2'));
    expect(tokens.value, isNull);
  });

  test('no token ever reaches the logs', () async {
    final lines = <String>[];
    final tokens = MemoryTokenStore('refresh-stored');
    final container = await _container(http, tokens, log: lines.add);
    await _started(container);
    await container.read(sessionProvider.notifier).login(email: 'jean@exemple.fr', password: 'motdepassesecret');
    server.validAccess = 'expired';
    await container.read(dioProvider).get<Object>('/api/v1/me');
    await container.read(sessionProvider.notifier).logout();

    final log = lines.join('\n');
    expect(lines, isNotEmpty);
    for (var i = 1; i <= server.issued; i++) {
      expect(log, isNot(contains('access-$i')));
      expect(log, isNot(contains('refresh-$i')));
    }
    expect(log, isNot(contains('refresh-stored')));
    expect(log, isNot(contains('motdepassesecret')));
    expect(log, contains('Authorization: <redacted>'));
  });
}
