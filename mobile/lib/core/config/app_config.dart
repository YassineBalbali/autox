import 'package:flutter/foundation.dart';

/// Where the API and the website are, decided at build time.
///
/// `--dart-define=AUTOX_API_BASE_URL=https://…` and `--dart-define=AUTOX_SITE_URL=https://…`
/// set them. Without them, a debug or profile build talks to this PC as the Android emulator
/// sees it (`10.0.2.2`: the backend on port 8081 from the repository's `.env`, the web app's
/// development server on 4200); a release build has no defaults and refuses to start, because a
/// release pointed at a developer's machine is a broken app, not a fallback.
@immutable
class AppConfig {
  const AppConfig({required this.apiBaseUrl, this.siteUrl = siteEmulatorDefault});

  /// The emulator's view of the host machine, and the port the backend runs on locally.
  static const String emulatorDefault = 'http://10.0.2.2:8081';

  /// The web app's development server, as the emulator sees it.
  static const String siteEmulatorDefault = 'http://10.0.2.2:4200';

  /// Without a trailing slash: the generated client appends paths starting with `/api`.
  final String apiBaseUrl;

  /// The website, for the pages the app opens in the browser (the legal pages).
  final String siteUrl;

  /// A page of the website: `site('/conditions-generales')`.
  Uri site(String path) => Uri.parse('$siteUrl$path');

  /// The configuration of this build. Throws [AppConfigException] for a release build
  /// without its addresses.
  static AppConfig fromEnvironment() => resolve(
    defined: const String.fromEnvironment('AUTOX_API_BASE_URL'),
    definedSite: const String.fromEnvironment('AUTOX_SITE_URL'),
    isRelease: kReleaseMode,
  );

  /// [fromEnvironment] with its inputs as arguments, so the rules can be tested.
  static AppConfig resolve({required String defined, required bool isRelease, String definedSite = ''}) => AppConfig(
    apiBaseUrl: _url('AUTOX_API_BASE_URL', defined, isRelease: isRelease, fallback: emulatorDefault),
    siteUrl: _url('AUTOX_SITE_URL', definedSite, isRelease: isRelease, fallback: siteEmulatorDefault),
  );

  static String _url(String name, String defined, {required bool isRelease, required String fallback}) {
    final value = defined.trim();
    if (value.isEmpty) {
      if (isRelease) {
        throw AppConfigException('$name is required for a release build (--dart-define=$name=https://…).');
      }
      return fallback;
    }

    final uri = Uri.tryParse(value);
    if (uri == null || !uri.hasScheme || uri.host.isEmpty || !(uri.isScheme('http') || uri.isScheme('https'))) {
      throw AppConfigException('$name is not an http(s) URL: "$value".');
    }
    // A release only ever speaks HTTPS: the release manifest does not permit cleartext, so an
    // http address would fail on every request rather than here, with a clear message.
    if (isRelease && !uri.isScheme('https')) {
      throw AppConfigException('$name must be https for a release build: "$value".');
    }
    return value.replaceFirst(RegExp(r'/+$'), '');
  }
}

class AppConfigException implements Exception {
  const AppConfigException(this.message);

  final String message;

  @override
  String toString() => 'AppConfigException: $message';
}
