import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:doran_mobile/data/repository.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';

import 'support/fixtures.dart';

class DelayedReadCache extends MemoryCache {
  final started = Completer<void>();
  final result = Completer<String?>();
  @override
  Future<String?> read(String key) {
    started.complete();
    return result.future;
  }
}

class DelayedWriteCache extends MemoryCache {
  final started = Completer<void>();
  final proceed = Completer<void>();
  @override
  Future<void> write(String key, String value) async {
    started.complete();
    await proceed.future;
    await super.write(key, value);
  }
}

void main() {
  test(
    'changing account while an offline cache read is pending hides old data',
    () async {
      var user = 'owner-a';
      final cache = DelayedReadCache();
      final repo = DoranRepository(
        client: MockClient((_) async => throw const SocketException('offline')),
        token: () async => 'token',
        userId: () => user,
        cache: cache,
        baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
        publishableKey: 'test',
      );
      final pending = repo.fetchDay('2026-11-14');
      await cache.started.future;
      user = 'owner-b';
      cache.result.complete(jsonEncode(dayJson(manualText: 'A의 비공개 일기')));
      await expectLater(
        pending,
        throwsA(
          isA<ApiException>().having((e) => e.code, 'code', 'account_changed'),
        ),
      );
    },
  );

  test(
    'changing account while cache write is pending removes the late old snapshot',
    () async {
      var user = 'owner-a';
      final cache = DelayedWriteCache();
      cache.values['doran:owner-b:/days/2026-11-14'] = 'B의 기존 일기';
      final repo = DoranRepository(
        client: MockClient(
          (_) async => jsonResponse(dayJson(manualText: 'A의 비공개 일기')),
        ),
        token: () async => 'token',
        userId: () => user,
        cache: cache,
        baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
        publishableKey: 'test',
      );
      final pending = repo.fetchDay('2026-11-14');
      await cache.started.future;
      user = 'owner-b';
      cache.proceed.complete();
      await expectLater(
        pending,
        throwsA(
          isA<ApiException>().having((e) => e.code, 'code', 'account_changed'),
        ),
      );
      expect(
        cache.values.containsKey('doran:owner-a:/days/2026-11-14'),
        isFalse,
      );
      expect(cache.values['doran:owner-b:/days/2026-11-14'], 'B의 기존 일기');
    },
  );
}
