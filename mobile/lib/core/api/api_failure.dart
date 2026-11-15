import 'package:autox_api/autox_api.dart' show ApiError, ErrorCode;
import 'package:dio/dio.dart';

import '../forms/validation.dart' show waitSentence;
import '../labels/labels.g.dart';

/// What went wrong with a call, in the terms a screen needs: a French sentence for the person,
/// whether trying again could help, and the API's stable code when there is one.
///
/// Worded as the web's `ErrorMessageService` words them, from `frontend/labels.fr.json`: the
/// code's sentence, with the wait added after a throttle; the generic sentence when there is no
/// code to word. Never the API's `message`, which is developer-facing English by contract
/// (docs/auth-decisions.md, section 2). Checked against the web by `frontend/fixtures/forms.json`.
sealed class ApiFailure implements Exception {
  const ApiFailure();

  /// French, for the person holding the phone.
  String get message;

  /// Whether the same request might succeed if sent again.
  bool get retryable;

  static ApiFailure from(Object error) {
    if (error is ApiFailure) {
      return error;
    }
    if (error is! DioException) {
      return const UnexpectedFailure();
    }
    // A request that failed because its token could not be renewed carries the reason.
    if (error.error case final ApiFailure inner) {
      return inner;
    }
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.transformTimeout:
        return const TimeoutFailure();
      case DioExceptionType.connectionError:
        return const OfflineFailure();
      case DioExceptionType.cancel:
        return const CancelledFailure();
      case DioExceptionType.badCertificate:
        return const UnexpectedFailure();
      case DioExceptionType.badResponse:
      case DioExceptionType.unknown:
        final status = error.response?.statusCode;
        if (status == null) {
          // No response at all: on Android a refused or unreachable host often lands here.
          return error.type == DioExceptionType.unknown ? const OfflineFailure() : const UnexpectedFailure();
        }
        return ServerFailure(status: status, problem: _problemOf(error.response?.data));
    }
  }

  static ApiError? _problemOf(Object? body) {
    if (body is! Map<String, dynamic>) {
      return null;
    }
    try {
      return ApiError.fromJson(body);
    } on Object {
      return null;
    }
  }
}

class OfflineFailure extends ApiFailure {
  const OfflineFailure();

  @override
  String get message => clientMessages['offline']!;

  @override
  bool get retryable => true;
}

/// Also what a cold start of the free Render instance looks like when it is slower than the
/// timeouts, hence the wording. App-only: a browser has no timeout to word.
class TimeoutFailure extends ApiFailure {
  const TimeoutFailure();

  @override
  String get message => 'Le serveur met du temps à répondre. Il est peut-être en train de démarrer : réessayez.';

  @override
  bool get retryable => true;
}

class CancelledFailure extends ApiFailure {
  const CancelledFailure();

  @override
  String get message => 'Requête annulée.';

  @override
  bool get retryable => true;
}

class ServerFailure extends ApiFailure {
  const ServerFailure({required this.status, this.problem});

  final int status;

  /// The RFC 9457 body, when the API sent one.
  final ApiError? problem;

  /// The API's stable code; null when there is none, or one newer than this build.
  ErrorCode? get code {
    final code = problem?.code;
    return code == ErrorCode.unknownDefaultOpenApi ? null : code;
  }

  /// How long to wait, when the server says (a throttle).
  int? get retryAfterSeconds => problem?.retryAfterSeconds;

  @override
  String get message {
    final code = this.code;
    if (code == null) {
      return clientMessages['unknown']!;
    }
    final sentence = errorCodeLabel(code);
    final wait = retryAfterSeconds;
    return wait == null ? sentence : '$sentence ${waitSentence(wait)}';
  }

  @override
  bool get retryable => status == 429 || status >= 500;
}

class UnexpectedFailure extends ApiFailure {
  const UnexpectedFailure();

  @override
  String get message => clientMessages['unknown']!;

  @override
  bool get retryable => true;
}
