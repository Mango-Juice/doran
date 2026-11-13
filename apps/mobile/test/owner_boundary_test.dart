import 'dart:async';

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:doran_mobile/main.dart' show DoranMaterialApp;
import 'package:doran_mobile/ui/editors.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fixtures.dart';

class CountingCache extends MemoryCache {
  int reads = 0, writes = 0, removals = 0;
  @override
  Future<String?> read(String key) async {
    reads++;
    return super.read(key);
  }

  @override
  Future<void> write(String key, String value) async {
    writes++;
    await super.write(key, value);
  }

  @override
  Future<void> remove(String key) async {
    removals++;
    await super.remove(key);
  }
}

class PendingDraftCache extends MemoryCache {
  final started = Completer<void>();
  final release = Completer<void>();
  @override
  Future<void> write(String key, String value) async {
    started.complete();
    await release.future;
    await super.write(key, value);
  }
}

Matcher get accountChanged =>
    isA<ApiException>().having((e) => e.code, 'code', 'account_changed');

DoranRepository ownerRepository({
  required CacheStore cache,
  required String Function() currentOwner,
  required Future<http.Response> Function(http.Request) handle,
  Future<String?> Function()? token,
}) => DoranRepository(
  client: MockClient(handle),
  token: token ?? () async => 'token-${currentOwner()}',
  userId: currentOwner,
  cache: cache,
  baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
  publishableKey: 'test',
);

void main() {
  test(
    'a retained repository rejects a new request before it can use another owner token',
    () async {
      var owner = 'owner-a';
      var tokenCalls = 0, networkCalls = 0;
      final repo = ownerRepository(
        cache: MemoryCache(),
        currentOwner: () => owner,
        token: () async {
          tokenCalls++;
          return 'token-b';
        },
        handle: (_) async {
          networkCalls++;
          return jsonResponse(dayJson());
        },
      );
      owner = 'owner-b';
      await expectLater(
        repo.request('PATCH', '/days/2026-11-14', {'manual_text': 'A의 개인 기록'}),
        throwsA(accountChanged),
      );
      expect(tokenCalls, 0);
      expect(networkCalls, 0);
      expect(repo.prefix, 'doran:owner-a:');
    },
  );

  for (final operation in ['load', 'save', 'clear']) {
    test(
      'a retained repository cannot $operation a draft in the next owner account',
      () async {
        var owner = 'owner-a';
        final cache = CountingCache();
        cache.values['doran:owner-b:draft:day:2026-11-14'] = 'B의 개인 기록';
        final repo = ownerRepository(
          cache: cache,
          currentOwner: () => owner,
          handle: (_) async => jsonResponse({}),
        );
        owner = 'owner-b';
        await expectLater(
          Future<Object?>.sync(
            () => switch (operation) {
              'load' => repo.loadDraft('day:2026-11-14'),
              'save' => repo.saveDraft('day:2026-11-14', 'A의 개인 기록'),
              _ => repo.clearDraft('day:2026-11-14'),
            },
          ),
          throwsA(accountChanged),
        );
        expect(cache.reads + cache.writes + cache.removals, 0);
        expect(cache.values['doran:owner-b:draft:day:2026-11-14'], 'B의 개인 기록');
      },
    );
  }

  test(
    'a retained repository cannot load the next owner cached diary',
    () async {
      var owner = 'owner-a';
      var networkCalls = 0;
      final cache = CountingCache();
      final repo = ownerRepository(
        cache: cache,
        currentOwner: () => owner,
        handle: (_) async {
          networkCalls++;
          return jsonResponse(dayJson());
        },
      );
      owner = 'owner-b';
      await expectLater(repo.fetchDay('2026-11-14'), throwsA(accountChanged));
      expect(networkCalls, 0);
      expect(cache.reads + cache.writes, 0);
    },
  );

  test('a newly created repository remains usable for the new owner', () async {
    var owner = 'owner-a';
    final cache = MemoryCache();
    final oldRepo = ownerRepository(
      cache: cache,
      currentOwner: () => owner,
      handle: (_) async => jsonResponse(dayJson()),
    );
    owner = 'owner-b';
    final newRepo = ownerRepository(
      cache: cache,
      currentOwner: () => owner,
      handle: (request) async {
        expect(request.headers['authorization'], 'Bearer token-owner-b');
        return jsonResponse(dayJson(manualText: 'B의 개인 기록'));
      },
    );
    await expectLater(oldRepo.fetchDay('2026-11-14'), throwsA(accountChanged));
    final current = await newRepo.fetchDay('2026-11-14');
    expect(current.value.displayText, 'B의 개인 기록');
    expect(newRepo.prefix, 'doran:owner-b:');
  });

  for (final nextOwner in <String?>['owner-b', null]) {
    testWidgets(
      'owner change to ${nextOwner ?? 'signed-out'} discards private routes and their actions',
      (tester) async {
        var privateActionCalls = 0;
        await tester.pumpWidget(
          DoranMaterialApp(
            ownerId: 'owner-a',
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => Scaffold(
                        body: TextButton(
                          onPressed: () {
                            privateActionCalls++;
                          },
                          child: const Text('A의 비공개 일기 저장'),
                        ),
                      ),
                    ),
                  ),
                  child: const Text('일기 열기'),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('일기 열기'));
        await tester.pumpAndSettle();
        expect(find.text('A의 비공개 일기 저장'), findsOneWidget);
        await tester.pumpWidget(
          DoranMaterialApp(
            ownerId: nextOwner,
            home: const Scaffold(body: Text('새 계정 화면')),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('새 계정 화면'), findsOneWidget);
        expect(find.text('A의 비공개 일기 저장'), findsNothing);
        expect(
          tester.state<NavigatorState>(find.byType(Navigator)).canPop(),
          isFalse,
        );
        expect(privateActionCalls, 0);
      },
    );
  }

  testWidgets(
    'an editor waiting to save a draft never submits old diary text to the next owner',
    (tester) async {
      var owner = 'owner-a';
      final cache = PendingDraftCache();
      cache.values['doran:owner-b:draft:day:2026-11-14'] = 'B의 기존 초안';
      final requests = <http.Request>[];
      final repo = ownerRepository(
        cache: cache,
        currentOwner: () => owner,
        handle: (request) async {
          requests.add(request);
          return jsonResponse(dayJson(manualText: '저장됨'));
        },
      );
      await tester.pumpWidget(
        DoranMaterialApp(
          ownerId: owner,
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => DayEditor(
                      repository: repo,
                      day: DoranDay.fromJson(dayJson(manualText: 'A의 비공개 일기')),
                    ),
                  ),
                ),
                child: const Text('일기 열기'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('일기 열기'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('내 글 저장'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('내 글 저장'));
      await tester.pump();
      expect(cache.started.isCompleted, isTrue);
      owner = 'owner-b';
      await tester.pumpWidget(
        DoranMaterialApp(
          ownerId: owner,
          home: const Scaffold(body: Text('B의 기록')),
        ),
      );
      await tester.pumpAndSettle();
      cache.release.complete();
      await tester.pumpAndSettle();
      expect(find.text('B의 기록'), findsOneWidget);
      expect(find.byType(DayEditor), findsNothing);
      expect(requests, isEmpty);
      expect(cache.values['doran:owner-b:draft:day:2026-11-14'], 'B의 기존 초안');
      expect(
        cache.values.containsKey('doran:owner-a:draft:day:2026-11-14'),
        isFalse,
      );
      expect(tester.takeException(), isNull);
    },
  );
}
