import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the refresh token lives between launches — and the only place it is ever kept.
///
/// The access token is never stored: it lives in memory for its 15 minutes and is renewed from
/// this one. An interface, so tests use [MemoryTokenStore] and the app the device keystore.
abstract interface class TokenStore {
  Future<String?> read();
  Future<void> write(String refreshToken);
  Future<void> clear();
}

/// The Android Keystore, through `flutter_secure_storage`.
///
/// What it encrypts cannot be decrypted on another device, which is why the manifest switches
/// backups off: a restored copy would be unreadable at best (docs/mobile-plan.md, section 1).
/// A read that fails — a keystore entry gone after a restore, a corrupted file — counts as no
/// session rather than a crash: the person signs in again.
class SecureTokenStore implements TokenStore {
  const SecureTokenStore([this._storage = const FlutterSecureStorage()]);

  final FlutterSecureStorage _storage;

  static const _key = 'autox.refreshToken';

  @override
  Future<String?> read() async {
    try {
      final value = await _storage.read(key: _key);
      return value == null || value.isEmpty ? null : value;
    } on Object {
      await clear();
      return null;
    }
  }

  @override
  Future<void> write(String refreshToken) => _storage.write(key: _key, value: refreshToken);

  @override
  Future<void> clear() async {
    try {
      await _storage.delete(key: _key);
    } on Object {
      // Nothing to do: an entry that cannot be deleted cannot be read either.
    }
  }
}

/// For tests, and nothing else: the token in a field. Records every write, so a test can see
/// the order of what happened.
class MemoryTokenStore implements TokenStore {
  MemoryTokenStore([this.value]);

  String? value;
  final List<String> writes = [];

  /// Called inside [write], before it completes: lets a test observe what is true at that
  /// moment (for instance, that no caller has been given the new access token yet).
  void Function(String refreshToken)? onWrite;

  @override
  Future<String?> read() async => value;

  @override
  Future<void> write(String refreshToken) async {
    onWrite?.call(refreshToken);
    await Future<void>.delayed(Duration.zero);
    value = refreshToken;
    writes.add(refreshToken);
  }

  @override
  Future<void> clear() async => value = null;
}
