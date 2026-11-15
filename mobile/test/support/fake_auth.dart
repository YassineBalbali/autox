import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';

import 'fake_http.dart';
import 'listings.dart';

/// The account endpoints of the API, faked: a numbered token pair per sign-in, registration or
/// refresh, only the latest access token accepted, and answers a test can change or hold.
/// What `GET /me/export` answers here: the shape of the real one, cut short.
const exportJson = {
  'exportedAt': '2026-11-15T10:00:00Z',
  'account': {'email': 'jean@exemple.fr', 'displayName': 'Jean Dupont'},
  'listings': <Object>[],
};

class FakeAccountApi {
  FakeAccountApi({this.emailVerified = true});

  bool emailVerified;
  int issued = 0;
  String? validAccess;

  /// The body of each request, by path, for the test to look at.
  final Map<String, List<Map<String, Object?>>> bodies = {};

  /// Holds an endpoint's answer until released.
  final Map<String, Completer<void>> gates = {};

  /// Replaces an endpoint's answer.
  final Map<String, FakeResponse> answers = {};

  Map<String, Object?> pair({String email = 'jean@exemple.fr', String name = 'Jean Dupont'}) {
    issued++;
    validAccess = 'access-$issued';
    return {
      'accessToken': 'access-$issued',
      'tokenType': 'Bearer',
      'expiresInSeconds': 900,
      'refreshToken': 'refresh-$issued',
      'user': user(email: email, name: name),
    };
  }

  Map<String, Object?> user({String email = 'jean@exemple.fr', String name = 'Jean Dupont'}) => {
    'id': 'u1',
    'email': email,
    'displayName': name,
    'role': 'PARTICULIER',
    'emailVerified': emailVerified,
    'emailVerificationPending': !emailVerified,
  };

  FutureOr<FakeResponse> Function(RequestOptions) _endpoint(
    String path,
    FakeResponse Function(RequestOptions) answer,
  ) => (request) async {
    final data = request.data;
    (bodies[path] ??= []).add(
      data is String && data.isNotEmpty ? jsonDecode(data) as Map<String, Object?> : <String, Object?>{},
    );
    await gates[path]?.future;
    return answers[path] ?? answer(request);
  };

  FakeResponse _authorised(RequestOptions request, FakeResponse answer) =>
      request.headers['Authorization'] == 'Bearer $validAccess'
      ? answer
      : const FakeResponse.json(401, {'status': 401, 'code': 'INVALID_TOKEN'});

  /// The app's whole API: these endpoints, and the reference data the screens around them read.
  FakeHttp http() => FakeHttp(
    {
      '/api/v1/reference/makes': const FakeResponse.json(200, makesJson),
      '/api/v1/reference/countries': const FakeResponse.json(200, [
        {'code': 'FR', 'name': 'France'},
        {'code': 'BE', 'name': 'Belgique'},
      ]),
      '/api/v1/listings/count': const FakeResponse.json(200, {'count': 12, 'capped': false}),
    },
    handlers: {
      '/api/v1/auth/login': _endpoint('/api/v1/auth/login', (_) => FakeResponse.json(200, pair())),
      '/api/v1/auth/register': _endpoint('/api/v1/auth/register', (request) {
        final body = jsonDecode(request.data as String) as Map<String, Object?>;
        return FakeResponse.json(201, pair(email: body['email']! as String, name: body['displayName']! as String));
      }),
      '/api/v1/auth/refresh': _endpoint('/api/v1/auth/refresh', (_) => FakeResponse.json(200, pair())),
      '/api/v1/auth/logout': _endpoint('/api/v1/auth/logout', (_) => const FakeResponse.json(204, null)),
      '/api/v1/auth/password/forgot': _endpoint(
        '/api/v1/auth/password/forgot',
        (_) => const FakeResponse.json(202, null),
      ),
      '/api/v1/me': _endpoint('/api/v1/me', (request) => _authorised(request, FakeResponse.json(200, user()))),
      '/api/v1/me/email/verification': _endpoint(
        '/api/v1/me/email/verification',
        (request) => _authorised(request, const FakeResponse.json(204, null)),
      ),
      '/api/v1/me/export': _endpoint(
        '/api/v1/me/export',
        (request) => _authorised(request, const FakeResponse.json(200, exportJson)),
      ),
      '/api/v1/me/deletion': _endpoint(
        '/api/v1/me/deletion',
        (request) => _authorised(request, const FakeResponse.json(204, null)),
      ),
    },
  );
}
