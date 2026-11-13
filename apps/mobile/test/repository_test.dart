import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'support/fixtures.dart';

void main() {
  late MemoryCache cache;
  late String user;
  late String? accessToken;
  DoranRepository repository(
    Future<http.Response> Function(http.Request) handle,
  ) {
    return DoranRepository(
      client: MockClient(handle),
      token: () async => accessToken,
      userId: () => user,
      cache: cache,
      baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
      publishableKey: 'sb_publishable_test',
    );
  }

  setUp(() {
    cache = MemoryCache();
    user = 'owner-a';
    accessToken = 'token-a';
  });

  test(
    'authenticated record reads keep date filters and cursor URL-safe',
    () async {
      final repo = repository((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/functions/v1/doran-api/v1/records');
        expect(request.url.queryParameters['date'], '2026-11-14');
        expect(request.url.queryParameters['cursor'], 'a/b+c=');
        expect(request.headers['authorization'], 'Bearer token-a');
        expect(request.headers['apikey'], 'sb_publishable_test');
        return jsonResponse({
          'items': [recordJson()],
          'next_cursor': null,
        });
      });
      final result = await repo.fetchRecords(
        date: '2026-11-14',
        cursor: 'a/b+c=',
      );
      expect(result.value.single.text, '원래 기록');
      expect(result.offline, isFalse);
    },
  );

  test('missing auth is rejected before accessing the network', () async {
    var called = false;
    accessToken = null;
    final repo = repository((_) async {
      called = true;
      return jsonResponse(dayJson());
    });
    await expectLater(
      repo.fetchDay('2026-11-14'),
      throwsA(isA<ApiException>().having((e) => e.status, 'status', 401)),
    );
    expect(called, isFalse);
  });

  test(
    'network failure returns only the active owner cache with offline flag',
    () async {
      var offline = false;
      final repo = repository((_) async {
        if (offline) throw const SocketException('disconnected');
        return jsonResponse(dayJson(manualText: '내 일기'));
      });
      await repo.fetchDay('2026-11-14');
      offline = true;
      final result = await repo.fetchDay('2026-11-14');
      expect(result.offline, isTrue);
      expect(result.value.displayText, '내 일기');
    },
  );

  test('another account cannot read cached diaries when offline', () async {
    var offline = false;
    final repo = repository((_) async {
      if (offline) throw const SocketException('disconnected');
      return jsonResponse(dayJson(manualText: 'A의 비공개 내용'));
    });
    await repo.fetchDay('2026-11-14');
    user = 'owner-b';
    accessToken = 'token-b';
    offline = true;
    await expectLater(
      repo.fetchDay('2026-11-14'),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'account_changed'),
      ),
    );
  });

  test(
    'revoked authorization does not silently fall back to cached content',
    () async {
      var revoked = false;
      final repo = repository(
        (_) async => revoked
            ? jsonResponse({
                'error': {'code': 'denied', 'message': '접근할 수 없습니다'},
              }, 403)
            : jsonResponse(dayJson(manualText: '비공개 내용')),
      );
      await repo.fetchDay('2026-11-14');
      revoked = true;
      await expectLater(
        repo.fetchDay('2026-11-14'),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 403)),
      );
    },
  );

  test(
    'an account switch during a read cannot install another owner response',
    () async {
      final response = Completer<http.Response>();
      final started = Completer<void>();
      final repo = repository((_) {
        started.complete();
        return response.future;
      });
      final pending = repo.fetchDay('2026-11-14');
      await started.future;
      user = 'owner-b';
      response.complete(jsonResponse(dayJson(manualText: 'A의 비공개 내용')));
      await expectLater(
        pending,
        throwsA(
          isA<ApiException>().having((e) => e.code, 'code', 'account_changed'),
        ),
      );
      expect(cache.values, isEmpty);
    },
  );

  test(
    'stale edit errors preserve drafts and never claim a successful save',
    () async {
      final repo = repository((request) async {
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        expect(body['version'], 2);
        expect(body['text'], '내가 작성한 변경');
        expect(body['request_id'], matches(RegExp(r'^[0-9a-f-]{36}$')));
        return jsonResponse({
          'error': {'code': 'version_conflict', 'message': '다른 기기에서 수정되었습니다'},
        }, 409);
      });
      await repo.saveDraft('record:record-1', '내가 작성한 변경');
      await expectLater(
        repo.updateRecord(
          DoranRecord.fromJson(recordJson()),
          text: '내가 작성한 변경',
        ),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 409)),
      );
      expect(await repo.loadDraft('record:record-1'), '내가 작성한 변경');
    },
  );

  test(
    'record correction sends exclusion and date changes together and invalidates stale snapshots',
    () async {
      final repo = repository((request) async {
        expect(request.method, 'PATCH');
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        expect(body['record_date'], '2026-11-13');
        expect(body['excluded_from_personalization'], isTrue);
        return jsonResponse({
          ...recordJson(version: 3),
          'record_date': '2026-11-13',
          'excluded_from_personalization': true,
        });
      });
      cache.values['doran:owner-a:/days/2026-11-14'] = '{}';
      cache.values['doran:owner-a:/records?limit=50'] = '{}';
      cache.values['doran:owner-b:/days/2026-11-14'] = '{}';
      await repo.saveDraft('record:record-1', '초안');
      final result = await repo.updateRecord(
        DoranRecord.fromJson(recordJson()),
        recordDate: '2026-11-13',
        excluded: true,
      );
      expect(result.excluded, isTrue);
      expect(result.version, 3);
      expect(
        cache.values.keys,
        isNot(contains('doran:owner-a:/days/2026-11-14')),
      );
      expect(cache.values.keys, contains('doran:owner-b:/days/2026-11-14'));
      expect(await repo.loadDraft('record:record-1'), '초안');
    },
  );

  for (final removeManual in [false, true]) {
    test(
      'record deletion forwards explicit manual diary scope $removeManual',
      () async {
        final repo = repository((request) async {
          expect(request.method, 'DELETE');
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          expect(body['version'], 2);
          expect(body['delete_manual_diary'], removeManual);
          return jsonResponse({'deleted': true});
        });
        await repo.saveDraft('record:record-1', '지울 초안');
        await repo.deleteRecord(
          DoranRecord.fromJson(recordJson()),
          deleteManualDiary: removeManual,
        );
        expect(await repo.loadDraft('record:record-1'), isNull);
      },
    );
  }

  test(
    'saving an empty manual diary does not adopt the generated version',
    () async {
      final repo = repository((request) async {
        expect(request.method, 'PATCH');
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        expect(body['manual_text'], '');
        expect(body['version'], 2);
        return jsonResponse(dayJson(manualText: '', version: 3));
      });
      final result = await repo.saveDay(DoranDay.fromJson(dayJson()), '');
      expect(result.displayText, isEmpty);
      expect(result.isManual, isTrue);
    },
  );

  test(
    'account deletion requires explicit confirmation and clears only its owner cache',
    () async {
      final repo = repository((request) async {
        expect(request.method, 'DELETE');
        expect(jsonDecode(request.body), {'confirmation': 'DELETE'});
        return jsonResponse({'deleted': true});
      });
      cache.values['doran:owner-a:/days/2026-11-14'] = '{}';
      cache.values['doran:owner-a:draft:record:record-1'] = '개인 기록';
      cache.values['doran:owner-b:/days/2026-11-14'] = '{}';
      await repo.deleteAccount();
      expect(cache.values.keys, ['doran:owner-b:/days/2026-11-14']);
    },
  );

  test('pairing codes are sent as strings so initial zeroes survive', () async {
    final repo = repository((request) async {
      expect(request.url.path.endsWith('/pairings/claim'), isTrue);
      expect((jsonDecode(request.body) as Map)['code'], '001234');
      return jsonResponse({
        'id': 'pair-1',
        'status': 'claimed',
        'device_label': '차량',
      });
    });
    expect((await repo.claimPairing('001234'))['status'], 'claimed');
  });

  test(
    'a mutation response from a signed-out account is never returned',
    () async {
      final response = Completer<http.Response>();
      final started = Completer<void>();
      final repo = repository((_) {
        started.complete();
        return response.future;
      });
      cache.values['doran:owner-b:/days/2026-11-14'] = '{}';
      final pending = repo.updateRecord(
        DoranRecord.fromJson(recordJson()),
        text: 'A의 새 비공개 내용',
      );
      await started.future;
      user = 'owner-b';
      accessToken = 'token-b';
      response.complete(
        jsonResponse(recordJson(text: 'A의 새 비공개 내용', version: 3)),
      );
      await expectLater(
        pending,
        throwsA(
          isA<ApiException>().having((e) => e.code, 'code', 'account_changed'),
        ),
      );
      expect(cache.values.keys, contains('doran:owner-b:/days/2026-11-14'));
    },
  );
}
