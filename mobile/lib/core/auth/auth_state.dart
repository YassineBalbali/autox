import 'package:autox_api/autox_api.dart' show UserSummary;
import 'package:flutter/foundation.dart';

import '../api/api_failure.dart';

/// Who is using the app, as far as it knows.
@immutable
sealed class AuthState {
  const AuthState();
}

/// At launch, before the stored session (if any) has been checked. The account screens show a
/// neutral wait, never the sign-in form, so a signed-in person does not see it flash.
class AuthUnknown extends AuthState {
  const AuthUnknown();
}

class SignedOut extends AuthState {
  const SignedOut({this.expired = false, this.refused});

  /// The server refused the session (expired, revoked, signed out elsewhere): "session
  /// expirée", said once.
  final bool expired;

  /// The server refused the account itself on a refresh (403: suspended, closed): its own
  /// sentence is said once instead, never "session expirée", which would invite signing in again.
  final ServerFailure? refused;
}

class SignedIn extends AuthState {
  const SignedIn(this.user);

  final UserSummary user;
}

/// A session is stored but could not be checked: no connection, a timeout, the server down. The
/// refresh token is kept, and the next request — or "Réessayer" — tries again.
class SessionUnavailable extends AuthState {
  const SessionUnavailable(this.failure);

  final ApiFailure failure;
}

/// The server refused the refresh token: the session is over.
class SessionExpired implements Exception {
  const SessionExpired();

  @override
  String toString() => 'SessionExpired';
}
