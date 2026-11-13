import 'dart:async';

import 'package:doran_mobile/data/secure_store.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

class ControlledSecureStorage extends FlutterSecureStorage {
  final Map<String, String> values = {};
  final blockedWriteStarted = Completer<void>();
  final releaseWrite = Completer<void>();
  int readAllCalls = 0;

  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (key.endsWith(':blocked')) {
      blockedWriteStarted.complete();
      await releaseWrite.future;
    }
    if (key.endsWith(':fail')) {
      throw StateError('simulated unavailable secure store');
    }
    if (value != null) {
      values[key] = value;
    }
  }

  @override
  Future<Map<String, String>> readAll({
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    readAllCalls++;
    return Map.of(values);
  }

  @override
  Future<void> delete({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    values.remove(key);
  }
}

void main() {
  test(
    'sign-out cache clearing waits for an older pending secure write and removes its result',
    () async {
      final storage = ControlledSecureStorage();
      final store = SecureStore(storage);
      storage.values['doran:owner-b:diary'] = '다른 계정의 기록';
      final pendingWrite = store.write('doran:owner-a:blocked', '로그아웃 전 기록');
      await storage.blockedWriteStarted.future;
      final clearing = store.clearPrefix('doran:owner-a:');
      await Future<void>.delayed(Duration.zero);
      expect(storage.readAllCalls, 0);
      storage.releaseWrite.complete();
      await pendingWrite;
      await clearing;
      expect(storage.values.keys, ['doran:owner-b:diary']);
    },
  );

  test(
    'a failed secure operation does not prevent later data removal',
    () async {
      final storage = ControlledSecureStorage();
      final store = SecureStore(storage);
      storage.values['doran:owner-a:diary'] = '삭제할 개인 기록';
      await expectLater(
        store.write('doran:owner-a:fail', '저장 시도'),
        throwsStateError,
      );
      await store.clearPrefix('doran:owner-a:');
      expect(storage.values, isEmpty);
      await store.write('doran:owner-b:diary', '정상 저장');
      expect(storage.values['doran:owner-b:diary'], '정상 저장');
    },
  );
}
