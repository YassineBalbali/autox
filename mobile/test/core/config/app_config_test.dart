import 'package:autox/core/config/app_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AppConfig.resolve', () {
    test('a debug build without a define uses the emulator host', () {
      final config = AppConfig.resolve(defined: '', isRelease: false);

      expect(config.apiBaseUrl, 'http://10.0.2.2:8081');
    });

    test('a release build without a define refuses to start', () {
      expect(() => AppConfig.resolve(defined: '  ', isRelease: true), throwsA(isA<AppConfigException>()));
    });

    test('a release build refuses plain http', () {
      expect(
        () => AppConfig.resolve(defined: 'http://autox-api.onrender.com', isRelease: true),
        throwsA(isA<AppConfigException>()),
      );
    });

    test('the defined address is used, without a trailing slash', () {
      final config = AppConfig.resolve(defined: 'https://autox-api.onrender.com/', isRelease: true);

      expect(config.apiBaseUrl, 'https://autox-api.onrender.com');
    });

    test('something that is not an http(s) URL is rejected', () {
      for (final value in ['autox-api.onrender.com', 'ftp://example.com', 'https://']) {
        expect(
          () => AppConfig.resolve(defined: value, isRelease: false),
          throwsA(isA<AppConfigException>()),
          reason: value,
        );
      }
    });
  });
}
