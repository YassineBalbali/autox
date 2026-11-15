import 'dart:convert';

import 'package:dio/dio.dart';

/// Request logs for development, with nothing in them that could open a session.
///
/// - `Authorization` and any cookie header are written as `<redacted>`;
/// - the bodies of the sensitive endpoints (everything under `/auth/`, the password change and
///   the account deletion) are never written, in either direction: they carry passwords and
///   tokens; nor is the data export's, which is everything the account holds;
/// - other bodies are written, cut short.
class RedactingLogInterceptor extends Interceptor {
  RedactingLogInterceptor(this._sink);

  final void Function(String line) _sink;

  static const _maxBody = 400;
  static const _secretHeaders = {'authorization', 'cookie', 'set-cookie'};

  static bool isSensitive(String path) {
    final location = Uri.tryParse(path)?.path ?? path;
    return location.contains('/api/v1/auth/') ||
        location.endsWith('/api/v1/me/password') ||
        location.endsWith('/api/v1/me/deletion') ||
        location.endsWith('/api/v1/me/export');
  }

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final headers = options.headers.entries
        .map(
          (entry) => '${entry.key}: ${_secretHeaders.contains(entry.key.toLowerCase()) ? '<redacted>' : entry.value}',
        )
        .join(', ');
    _sink('→ ${options.method} ${options.uri.path}${options.uri.hasQuery ? '?${options.uri.query}' : ''} [$headers]');
    if (options.data != null) {
      _sink('  body: ${_body(options.uri.path, options.data)}');
    }
    handler.next(options);
  }

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    final request = response.requestOptions;
    _sink('← ${response.statusCode} ${request.method} ${request.uri.path}');
    if (response.data != null) {
      _sink('  body: ${_body(request.uri.path, response.data)}');
    }
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    final request = err.requestOptions;
    final status = err.response?.statusCode;
    _sink('✕ ${status ?? err.type.name} ${request.method} ${request.uri.path}');
    final data = err.response?.data;
    if (data != null) {
      _sink('  body: ${_body(request.uri.path, data)}');
    }
    handler.next(err);
  }

  String _body(String path, Object? data) {
    if (isSensitive(path)) {
      return '<redacted>';
    }
    final text = data is String ? data : _encode(data);
    return text.length <= _maxBody ? text : '${text.substring(0, _maxBody)}…';
  }

  static String _encode(Object? data) {
    try {
      return jsonEncode(data);
    } on Object {
      return '<${data.runtimeType}>';
    }
  }
}
