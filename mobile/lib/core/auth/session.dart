import 'dart:async';

import 'package:autox_api/autox_api.dart'
    show AuthResponse, AuthenticationApi, LoginRequest, RefreshRequest, RegisterRequest, UserSummary;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_failure.dart';
import '../api/api_providers.dart';
import 'auth_state.dart';
import 'token_store.dart';

export 'auth_state.dart';

/// Where the refresh token is kept: the device keystore. Overridden in tests.
final tokenStoreProvider = Provider<TokenStore>((ref) => const SecureTokenStore());

/// What the sessions screen of the site lists for this phone. An access token carries no
/// reference to its session, so the label is how a client finds its own entry there.
const deviceLabel = 'Application Android';

/// The session: who is signed in, the access token (in memory only) and the one way the refresh
/// token is ever sent.
///
/// **The refresh rule.** The API rotates the refresh token on every use and treats a second
/// presentation of a rotated one as theft: it revokes the whole family, signing the person out
/// of every device (docs/auth-decisions.md). So two refreshes must never send the same token.
/// That is guaranteed by construction, not by timing:
///
/// - [refreshAccessToken] is the only code that sends a refresh token to `/auth/refresh`;
/// - it hands every caller the same future while one is in flight — the slot is filled before
///   the first `await`, so there is no gap for a second caller to start another;
/// - the new refresh token is written to storage before that future completes, so the next
///   refresh, whenever it comes, reads the new one;
/// - sign-out waits for a refresh in flight before it reads the token to revoke.
class SessionController extends Notifier<AuthState> {
  String? _accessToken;
  Future<String>? _refreshing;

  TokenStore get _store => ref.read(tokenStoreProvider);
  AuthenticationApi get _api => ref.read(authenticationApiProvider);

  /// The access token for the next request, or null. Never stored, never logged.
  String? get accessToken => _accessToken;

  /// Whether a refresh token may exist: worth trying a refresh on a 401.
  bool get mayRefresh => state is SignedIn || state is SessionUnavailable;

  @override
  AuthState build() {
    unawaited(_restore());
    return const AuthUnknown();
  }

  /// At launch: a stored refresh token is exchanged, through the one refresh path, for an access
  /// token and the account's summary.
  Future<void> _restore() async {
    final stored = await _store.read();
    if (!ref.mounted) {
      return;
    }
    if (stored == null) {
      state = const SignedOut();
      return;
    }
    // A failure has already set the state (signed out, or unavailable).
    await refreshAccessToken().then<void>((_) {}, onError: (Object _) {});
  }

  /// "Réessayer", when the stored session could not be checked.
  Future<void> retryRestore() async {
    if (state is SessionUnavailable) {
      state = const AuthUnknown();
      await refreshAccessToken().then<void>((_) {}, onError: (Object _) {});
    }
  }

  /// A new access token, from the stored refresh token. Every caller while one is in flight
  /// gets the same future. Throws [SessionExpired] when the server refuses the token (the
  /// session is then cleared), or an [ApiFailure] when it could not be reached (the token is
  /// kept, for the next try).
  Future<String> refreshAccessToken() {
    return _refreshing ??= _refresh().whenComplete(() => _refreshing = null);
  }

  Future<String> _refresh() async {
    final token = await _store.read();
    if (token == null) {
      _clearMemory(const SignedOut(expired: true));
      throw const SessionExpired();
    }
    final AuthResponse response;
    try {
      final reply = await _api.refresh(refreshRequest: RefreshRequest(refreshToken: token));
      response = reply.data!;
    } on Object catch (error) {
      final failure = ApiFailure.from(error);
      if (failure is ServerFailure && (failure.status == 401 || failure.status == 403)) {
        await _store.clear();
        _clearMemory(const SignedOut(expired: true));
        throw const SessionExpired();
      }
      if (ref.mounted && state is! SignedIn) {
        state = SessionUnavailable(failure);
      }
      throw failure;
    }
    await _adopt(response);
    return _accessToken!;
  }

  /// Takes a token pair into the session: the refresh token into storage **first**, then the
  /// access token into memory. Whoever is waiting on a refresh resumes only after both.
  Future<void> _adopt(AuthResponse response) async {
    final refresh = response.refreshToken;
    final access = response.accessToken;
    final user = response.user;
    if (refresh == null || access == null || user == null) {
      throw const UnexpectedFailure();
    }
    await _store.write(refresh);
    _accessToken = access;
    if (ref.mounted) {
      state = SignedIn(user);
    }
  }

  Future<UserSummary> login({required String email, required String password}) async {
    try {
      final reply = await _api.login(
        loginRequest: LoginRequest(email: email.trim(), password: password, deviceLabel: deviceLabel),
      );
      await _adopt(reply.data!);
      return reply.data!.user!;
    } on Object catch (error) {
      throw ApiFailure.from(error);
    }
  }

  /// Registration answers with a token pair, as sign-in does: the new account is signed in.
  Future<UserSummary> register(RegisterRequest request) async {
    try {
      final reply = await _api.register(registerRequest: request);
      await _adopt(reply.data!);
      return reply.data!.user!;
    } on Object catch (error) {
      throw ApiFailure.from(error);
    }
  }

  /// Signed out here at once, whatever the server says; the server is then asked to end the
  /// session, and its answer — or its silence, offline — changes nothing on the phone.
  Future<void> logout() async {
    // A refresh in flight would write a new token after we cleared: wait for it first.
    await _refreshing?.then<void>((_) {}, onError: (Object _) {});
    final token = await _store.read();
    await _store.clear();
    _clearMemory(const SignedOut());
    if (token != null) {
      try {
        await _api.logout(refreshRequest: RefreshRequest(refreshToken: token));
      } on Object {
        // Already signed out on the phone; the token expires on its own.
      }
    }
  }

  /// After the account was deleted: the server has already ended every session.
  Future<void> forget() async {
    await _refreshing?.then<void>((_) {}, onError: (Object _) {});
    await _store.clear();
    _clearMemory(const SignedOut());
  }

  /// The signed-in account's summary changed (a profile read): kept in the state.
  void updateUser(UserSummary user) {
    if (state is SignedIn) {
      state = SignedIn(user);
    }
  }

  /// The "session expirée" notice has been shown: not again.
  void acknowledgeExpiry() {
    if (state case SignedOut(expired: true)) {
      state = const SignedOut();
    }
  }

  void _clearMemory(AuthState next) {
    _accessToken = null;
    if (ref.mounted) {
      state = next;
    }
  }
}

final sessionProvider = NotifierProvider<SessionController, AuthState>(SessionController.new);
