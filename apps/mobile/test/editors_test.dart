import 'dart:convert';

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:doran_mobile/ui/editors.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'support/fixtures.dart';

void main() {
  late MemoryCache cache;
  DoranRepository repository(
    Future<http.Response> Function(http.Request) handle,
  ) => DoranRepository(
    client: MockClient(handle),
    token: () async => 'token-a',
    userId: () => 'owner-a',
    cache: cache,
    baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
    publishableKey: 'test',
  );
  setUp(() => cache = MemoryCache());

  testWidgets(
    'conflicting record save preserves typed text and requires an explicit overwrite',
    (tester) async {
      var patches = 0;
      final repo = repository((request) async {
        if (request.method == 'PATCH') {
          patches++;
          return jsonResponse({
            'error': {'code': 'version_conflict', 'message': '버전 충돌'},
          }, 409);
        }
        return jsonResponse(recordJson(text: '다른 기기에서 고친 최신 글', version: 3));
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
      await tester.enterText(find.byType(TextField), '지금 작성 중인 내 글');
      final save = find.text('변경 사항 저장');
      await tester.scrollUntilVisible(
        save,
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(find.text('다른 곳에서 기록이 바뀌었어요'), findsOneWidget);
      expect(find.text('다른 기기에서 고친 최신 글'), findsOneWidget);
      expect(await repo.loadDraft('record:record-1'), '지금 작성 중인 내 글');
      await tester.tap(find.text('계속 수정'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byType(TextField),
        -300,
        scrollable: find.byType(Scrollable).first,
      );
      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.controller!.text, '지금 작성 중인 내 글');
      expect(patches, 1);
    },
  );

  testWidgets(
    'day save conflict keeps manual text and leaves a recoverable draft',
    (tester) async {
      final repo = repository(
        (_) async => jsonResponse({
          'error': {'code': 'version_conflict', 'message': '버전 충돌'},
        }, 409),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: doranTheme(),
          home: DayEditor(
            repository: repo,
            day: DoranDay.fromJson(dayJson(manualText: '내 이전 일기')),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '잃어버리면 안 되는 수정');
      await tester.scrollUntilVisible(
        find.text('내 글 저장'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('내 글 저장'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.textContaining('내 글은 기기에 보관했어요'),
        -300,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.textContaining('내 글은 기기에 보관했어요'), findsOneWidget);
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        '잃어버리면 안 되는 수정',
      );
      expect(await repo.loadDraft('day:2026-11-14'), '잃어버리면 안 되는 수정');
    },
  );

  testWidgets(
    'comparison view does not adopt new AI writing when keep-my-writing is chosen',
    (tester) async {
      var calls = 0;
      final repo = repository((_) async {
        calls++;
        return jsonResponse(dayJson());
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: doranTheme(),
          home: DayEditor(
            repository: repo,
            day: DoranDay.fromJson(dayJson(manualText: '내가 직접 다듬은 문장')),
            compare: true,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('새 AI 일기 전체 반영'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('새 AI 일기 전체 반영'));
      await tester.pumpAndSettle();
      expect(find.text('새 AI 일기를 반영할까요?'), findsOneWidget);
      await tester.tap(find.text('내 글 유지'));
      await tester.pumpAndSettle();
      expect(calls, 0);
      await tester.scrollUntilVisible(
        find.byType(TextField),
        -300,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        '내가 직접 다듬은 문장',
      );
    },
  );

  testWidgets(
    'record delete preserves manual day unless its scope is selected',
    (tester) async {
      Map<String, dynamic>? deletedBody;
      final repo = repository((request) async {
        deletedBody = jsonDecode(request.body) as Map<String, dynamic>;
        return jsonResponse({'deleted': true});
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
      await tester.tap(find.byTooltip('기록 삭제'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
        isFalse,
      );
      expect(deletedBody, isNull);
      await tester.tap(find.text('이 날짜에 직접 수정한 하루 일기도 삭제'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, '삭제'));
      await tester.pumpAndSettle();
      expect(deletedBody!['delete_manual_diary'], isTrue);
      expect(deletedBody!['version'], 2);
    },
  );
}
