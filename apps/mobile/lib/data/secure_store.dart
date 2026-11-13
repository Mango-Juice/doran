import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'repository.dart';

class SecureStore implements CacheStore {
  final FlutterSecureStorage storage;
  const SecureStore([this.storage = const FlutterSecureStorage()]);
  static Future<void> _pending = Future<void>.value();
  Future<T> _serialize<T>(Future<T> Function() operation) {
    final result = _pending.then((_) => operation());
    _pending = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  @override
  Future<String?> read(String key) => _serialize(() => storage.read(key: key));
  @override
  Future<void> write(String key, String value) =>
      _serialize(() => storage.write(key: key, value: value));
  @override
  Future<void> remove(String key) => _serialize(() => storage.delete(key: key));
  @override
  Future<void> clearPrefix(String prefix) => _serialize(() async {
    final values = await storage.readAll();
    for (final key in values.keys.where((k) => k.startsWith(prefix))) {
      await storage.delete(key: key);
    }
  });
}

class SecureSessionStorage extends LocalStorage {
  final SecureStore store;
  const SecureSessionStorage(this.store);
  static const key = 'doran-auth-session';
  @override
  Future<void> initialize() async {}
  @override
  Future<bool> hasAccessToken() async => await store.read(key) != null;
  @override
  Future<String?> accessToken() => store.read(key);
  @override
  Future<void> persistSession(String session) => store.write(key, session);
  @override
  Future<void> removePersistedSession() => store.remove(key);
}

class SecurePkceStorage extends GotrueAsyncStorage {
  final SecureStore store;
  SecurePkceStorage(this.store);
  @override
  Future<String?> getItem({required String key}) =>
      store.read('doran-pkce:$key');
  @override
  Future<void> setItem({required String key, required String value}) =>
      store.write('doran-pkce:$key', value);
  @override
  Future<void> removeItem({required String key}) =>
      store.remove('doran-pkce:$key');
}
