import 'package:flutter/foundation.dart';

/// Where the API is, decided at build time.
///
/// `--dart-define=AUTOX_API_BASE_URL=https://…` sets it. Without it, a debug or profile build
/// talks to the backend on this PC as the Android emulator sees it (`10.0.2.2`, port 8081 from
/// the repository's `.env`); a release build has no default and refuses to start, because a
/// release pointed at a developer's machine is a broken app, not a fallback.
@immutable
class AppConfig {
  const AppConfig({required this.apiBaseUrl});

  /// The emulator's view of the host machine, and the port the backend runs on locally.
  static const String emulatorDefault = 'http://10.0.2.2:8081';

  /// Without a trailing slash: the generated client appends paths starting with `/api`.
  final String apiBaseUrl;

  /// The configuration of this build. Throws [AppConfigException] for a release build
  /// without an API address.
  static AppConfig fromEnvironment() =>
      resolve(defined: const String.fromEnvironment('AUTOX_API_BASE_URL'), isRelease: kReleaseMode);

  /// [fromEnvironment] with its inputs as arguments, so the rules can be tested.
  static AppConfig resolve({required String defined, required bool isRelease}) {
    final value = defined.trim();
    if (value.isEmpty) {
      if (isRelease) {
        throw const AppConfigException(
          'AUTOX_API_BASE_URL is required for a release build '
          '(--dart-define=AUTOX_API_BASE_URL=https://…).',
        );
      }
      return const AppConfig(apiBaseUrl: emulatorDefault);
    }

    final uri = Uri.tryParse(value);
    if (uri == null || !uri.hasScheme || uri.host.isEmpty || !(uri.isScheme('http') || uri.isScheme('https'))) {
      throw AppConfigException('AUTOX_API_BASE_URL is not an http(s) URL: "$value".');
    }
    // A release only ever speaks HTTPS: the release manifest does not permit cleartext, so an
    // http address would fail on every request rather than here, with a clear message.
    if (isRelease && !uri.isScheme('https')) {
      throw AppConfigException('AUTOX_API_BASE_URL must be https for a release build: "$value".');
    }
    return AppConfig(apiBaseUrl: value.replaceFirst(RegExp(r'/+$'), ''));
  }
}

class AppConfigException implements Exception {
  const AppConfigException(this.message);

  final String message;

  @override
  String toString() => 'AppConfigException: $message';
}
