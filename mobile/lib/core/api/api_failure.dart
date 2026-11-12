import 'package:autox_api/autox_api.dart' show ApiError, ErrorCode;
import 'package:dio/dio.dart';

/// What went wrong with a call, in the terms a screen needs: a French sentence for the person,
/// whether trying again could help, and the API's stable code when there is one.
///
/// This is the first cut, for the screens of milestone 1. The full French table of
/// [ErrorCode]s comes with sign-in (milestone 4), from the shared labels file the web will read
/// too; until then an API error is shown by its kind, never by the API's `message`, which is
/// developer-facing English by contract (docs/auth-decisions.md, section 2).
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
  String get message => 'Impossible de joindre AutoX. Vérifiez votre connexion internet.';

  @override
  bool get retryable => true;
}

/// Also what a cold start of the free Render instance looks like when it is slower than the
/// timeouts, hence the wording.
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

  ErrorCode? get code => problem?.code;

  @override
  String get message {
    if (status == 429) {
      return 'Trop de tentatives. Patientez un instant, puis réessayez.';
    }
    if (status == 404) {
      return 'Cette page n’existe pas ou plus.';
    }
    if (status >= 500) {
      return 'AutoX rencontre un problème. Réessayez dans un instant.';
    }
    return 'La demande n’a pas pu aboutir.';
  }

  @override
  bool get retryable => status == 429 || status >= 500;
}

class UnexpectedFailure extends ApiFailure {
  const UnexpectedFailure();

  @override
  String get message => 'Une erreur inattendue s’est produite.';

  @override
  bool get retryable => true;
}
