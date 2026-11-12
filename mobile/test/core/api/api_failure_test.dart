import 'package:autox/core/api/api_failure.dart';
import 'package:autox_api/autox_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

DioException _transport(DioExceptionType type) => DioException(
  requestOptions: RequestOptions(path: '/x'),
  type: type,
);

DioException _status(int status, [Object? body]) {
  final options = RequestOptions(path: '/x');
  return DioException(
    requestOptions: options,
    type: DioExceptionType.badResponse,
    response: Response<Object>(requestOptions: options, statusCode: status, data: body),
  );
}

void main() {
  test('timeouts read as a server that may be starting', () {
    for (final type in [
      DioExceptionType.connectionTimeout,
      DioExceptionType.receiveTimeout,
      DioExceptionType.sendTimeout,
    ]) {
      final failure = ApiFailure.from(_transport(type));
      expect(failure, isA<TimeoutFailure>());
      expect(failure.message, contains('démarrer'));
      expect(failure.retryable, isTrue);
    }
  });

  test('no connection reads as offline', () {
    expect(ApiFailure.from(_transport(DioExceptionType.connectionError)), isA<OfflineFailure>());
    expect(ApiFailure.from(_transport(DioExceptionType.unknown)), isA<OfflineFailure>());
  });

  test('a problem body is parsed and its code kept', () {
    final failure = ApiFailure.from(
      _status(429, {
        'status': 429,
        'code': 'TOO_MANY_ATTEMPTS',
        'message': 'Too many attempts',
        'retryAfterSeconds': 30,
      }),
    );

    expect(failure, isA<ServerFailure>());
    final server = failure as ServerFailure;
    expect(server.code, ErrorCode.TOO_MANY_ATTEMPTS);
    expect(server.problem?.retryAfterSeconds, 30);
    expect(server.retryable, isTrue);
    // French, never the API's English message.
    expect(server.message, startsWith('Trop de tentatives'));
  });

  test('a 4xx is not worth retrying, a 5xx is', () {
    expect(ApiFailure.from(_status(404)).retryable, isFalse);
    expect(ApiFailure.from(_status(400, 'not json')).retryable, isFalse);
    expect(ApiFailure.from(_status(503)).retryable, isTrue);
  });

  test('anything else is unexpected, and an ApiFailure passes through', () {
    expect(ApiFailure.from(StateError('boom')), isA<UnexpectedFailure>());
    const offline = OfflineFailure();
    expect(ApiFailure.from(offline), same(offline));
  });
}
