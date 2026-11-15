import 'package:dio/dio.dart';

import 'session.dart';

/// Puts the access token on every request, and on a 401 renews it once and sends the request
/// again — once.
///
/// - The refresh is [SessionController.refreshAccessToken]: one shared future, however many
///   requests fail together. They all wait on it and are all retried with its token.
/// - A request that was sent with an older token than the session now holds (another request
///   renewed it meanwhile) is simply retried with the current one: no refresh.
/// - A retried request that answers 401 again is passed on as it is: never a second retry.
/// - The `/auth/*` calls do not use this client at all (`authDioProvider`), so the refresh can
///   never come back through here.
class AuthInterceptor extends Interceptor {
  /// [_dio] is the client this interceptor sits on, for the retry; [_session] is read on each
  /// request rather than held, so the session can be built after the client.
  AuthInterceptor(this._dio, this._session);

  final Dio _dio;
  final SessionController Function() _session;

  static const _retriedKey = 'autox.retried';
  static const _sentTokenKey = 'autox.sentToken';

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final token = _session().accessToken;
    options.extra[_sentTokenKey] = token;
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    } else {
      options.headers.remove('Authorization');
    }
    handler.next(options);
  }

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    final options = err.requestOptions;
    final session = _session();
    if (err.response?.statusCode != 401 || options.extra[_retriedKey] == true || !session.mayRefresh) {
      handler.next(err);
      return;
    }

    final String token;
    try {
      final sent = options.extra[_sentTokenKey];
      final current = session.accessToken;
      token = current != null && current != sent ? current : await session.refreshAccessToken();
    } on SessionExpired {
      // The session is over (already cleared): the caller sees the request's own 401.
      handler.next(err);
      return;
    } on Object catch (failure) {
      // The refresh could not be made (offline, a timeout, the server down): the token is kept
      // for the next request, and this one fails with the reason.
      handler.next(DioException(requestOptions: options, error: failure, type: DioExceptionType.unknown));
      return;
    }

    try {
      final retry = await _dio.fetch<dynamic>(
        options.copyWith(
          headers: {...options.headers, 'Authorization': 'Bearer $token'},
          extra: {...options.extra, _retriedKey: true},
        ),
      );
      handler.resolve(retry);
    } on DioException catch (retryError) {
      handler.next(retryError);
    }
  }
}
