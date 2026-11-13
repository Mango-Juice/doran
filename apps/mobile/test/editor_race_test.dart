import 'dart:async';
import 'dart:convert';

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:doran_mobile/ui/editors.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fixtures.dart';

class SlowDraftCache extends MemoryCache {
  final restoredDraft = Completer<String?>();
  @override
  Future<String?> read(String key) => restoredDraft.future;
}

DoranRepository makeRepository(
  CacheStore cache,
  Future<http.Response> Function(http.Request) handler,
) => DoranRepository(
  client: MockClient(handler),
  token: () async => 'token',
  userId: () => 'owner',
  cache: cache,
  baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
  publishableKey: 'test',
);

void main() {
  testWidgets(
    'text conflict resolution preserves remotely changed privacy and diary date',
    (tester) async {
      final patches = <Map<String, dynamic>>[];
      final newest = {
        ...recordJson(text: '다른 기기의 수정', version: 3),
        'excluded_from_personalization': true,
        'record_date': '2026-11-13',
      };
      final repo = makeRepository(MemoryCache(), (request) async {
        if (request.method == 'PATCH') {
          patches.add(jsonDecode(request.body) as Map<String, dynamic>);
          if (patches.length == 1) {
            return jsonResponse({
              'error': {'code': 'version_conflict', 'message': '충돌'},
            }, 409);
          }
          return jsonResponse({...newest, 'text': '내가 쓴 새 글', 'version': 4});
        }
        return jsonResponse(newest);
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: doranTheme(),
          home: RecordEditor(
            repository: repo,
            record: DoranRecord.fromJson(recordJson()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '내가 쓴 새 글');
      await tester.scrollUntilVisible(
        find.text('변경 사항 저장'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('변경 사항 저장'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('내 글로 덮어쓰기'));
      await tester.pumpAndSettle();
      expect(patches, hasLength(2));
      expect(patches.last['version'], 3);
      expect(patches.last['text'], '내가 쓴 새 글');
      for (final body in patches) {
        expect(body.containsKey('excluded_from_personalization'), isFalse);
        expect(body.containsKey('record_date'), isFalse);
      }
    },
  );

  for (final dayEditor in [false, true]) {
    testWidgets(
      'late ${dayEditor ? 'day' : 'record'} draft restoration never replaces newly entered text',
      (tester) async {
        final cache = SlowDraftCache();
        final repo = makeRepository(cache, (_) async => jsonResponse({}));
        final editor = dayEditor
            ? DayEditor(
                repository: repo,
                day: DoranDay.fromJson(dayJson(manualText: '내 이전 일기')),
              )
            : RecordEditor(
                repository: repo,
                record: DoranRecord.fromJson(recordJson()),
              );
        await tester.pumpWidget(MaterialApp(theme: doranTheme(), home: editor));
        final typedText = dayEditor ? '' : '지금 쓴 문장';
        await tester.enterText(find.byType(TextField), typedText);
        await tester.pump();
        cache.restoredDraft.complete('기기에 남아 있던 오래된 초안');
        await tester.pumpAndSettle();
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          typedText,
        );
        expect(find.textContaining('불러왔어요'), findsNothing);
      },
    );
  }
}
