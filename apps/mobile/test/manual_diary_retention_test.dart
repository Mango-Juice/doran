import 'dart:convert';

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:doran_mobile/ui/editors.dart';
import 'package:doran_mobile/ui/journal.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fixtures.dart';

DoranRepository _repository(
  Future<http.Response> Function(http.Request) handler,
) => DoranRepository(
  client: MockClient(handler),
  token: () async => 'synthetic-token',
  userId: () => 'synthetic-owner',
  cache: MemoryCache(),
  baseUrl: 'https://example.test/v1',
  publishableKey: 'synthetic',
);

Map<String, dynamic> _emptyGeneratedDay(String? manual, {int version = 15}) => {
  ...dayJson(manualText: manual, version: version),
  'date': kstDate(),
  'status': 'empty',
  'generated_text': '',
  // This flag reflects a new generation revision, not necessarily new text.
  'has_new_generated': true,
};

Widget _journal(DoranRepository repository, {int revision = 0}) => MaterialApp(
  theme: doranTheme(),
  home: Scaffold(
    body: JournalPage(repository: repository, refreshRevision: revision),
  ),
);

void main() {
  testWidgets(
    'manual day survives last-record refresh and can be edited and explicitly deleted',
    (tester) async {
      tester.view.physicalSize = const Size(430, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var noRecords = false;
      var deleted = false;
      var manual = '가상 검증: 개별 기록을 삭제해도 남겨둘 내 일기';
      var version = 14;
      final writes = <http.Request>[];
      final repo = _repository((request) async {
        if (request.method == 'PATCH') {
          writes.add(request);
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          expect(body['version'], 15);
          expect(body['request_id'], isA<String>());
          manual = body['manual_text'] as String;
          version++;
        } else if (request.method == 'DELETE') {
          writes.add(request);
          expect(request.url.path, '/v1/days/${kstDate()}');
          expect(jsonDecode(request.body)['version'], 16);
          deleted = true;
        }
        if (request.url.path.endsWith('/records')) {
          return jsonResponse({
            'items': noRecords ? [] : [recordJson()],
            'next_cursor': null,
          });
        }
        return jsonResponse(
          deleted
              ? _emptyGeneratedDay(null)
              : noRecords
              ? _emptyGeneratedDay(manual, version: version)
              : {
                  ...dayJson(manualText: manual, version: version),
                  'date': kstDate(),
                },
        );
      });
      await tester.pumpWidget(_journal(repo));
      await tester.pumpAndSettle();
      expect(find.text(manual), findsOneWidget);
      noRecords = true;
      version = 15;
      await tester.pumpWidget(_journal(repo, revision: 1));
      await tester.pumpAndSettle();
      expect(find.text(manual), findsOneWidget);
      expect(find.text('직접 다듬은 글'), findsOneWidget);
      expect(find.text('아직 나눈 이야기가 없어요'), findsNothing);
      expect(find.text('차량 연결하기'), findsNothing);
      expect(find.text('새 AI 일기 비교'), findsNothing);
      expect(find.text('이 날짜에 남아 있는 개별 기록은 없어요'), findsOneWidget);

      await tester.tap(find.text('다듬기'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        manual,
      );
      expect(find.text('새 AI 일기 전체 반영'), findsNothing);
      await tester.enterText(
        find.byType(TextField),
        '가상 검증: 남겨 둔 일기를 다시 다듬었어요',
      );
      await tester.tap(find.text('내 글 저장'));
      await tester.pumpAndSettle();
      expect(find.text(manual), findsOneWidget);
      expect(writes.where((r) => r.method == 'PATCH'), hasLength(1));

      await tester.ensureVisible(find.text('이 날짜의 모든 기록 삭제'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('이 날짜의 모든 기록 삭제'));
      await tester.pumpAndSettle();
      expect(find.textContaining('직접 수정한 일기'), findsOneWidget);
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(writes.where((r) => r.method == 'DELETE'), isEmpty);
      expect(find.text(manual), findsOneWidget);
      await tester.tap(find.text('이 날짜의 모든 기록 삭제'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('모두 삭제'));
      await tester.pumpAndSettle();
      expect(writes.where((r) => r.method == 'DELETE'), hasLength(1));
      expect(find.text(manual), findsNothing);
      expect(find.text('아직 나눈 이야기가 없어요'), findsOneWidget);
    },
  );

  testWidgets(
    'an intentionally empty manual draft remains editable while a truly empty day does not',
    (tester) async {
      String? manual = '';
      final repo = _repository(
        (request) async => jsonResponse(
          request.url.path.endsWith('/records')
              ? {'items': [], 'next_cursor': null}
              : _emptyGeneratedDay(manual),
        ),
      );
      await tester.pumpWidget(_journal(repo));
      await tester.pumpAndSettle();
      expect(find.text('직접 다듬은 글'), findsOneWidget);
      expect(find.text('아직 작성된 내용이 없어요'), findsOneWidget);
      expect(find.text('다듬기'), findsOneWidget);
      expect(find.text('새 AI 일기 비교'), findsNothing);
      expect(find.text('아직 나눈 이야기가 없어요'), findsNothing);
      await tester.ensureVisible(find.text('다듬기'));
      await tester.tap(find.text('다듬기'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        isEmpty,
      );
      await tester.pageBack();
      await tester.pumpAndSettle();
      manual = null;
      await tester.pumpWidget(_journal(repo, revision: 1));
      await tester.pumpAndSettle();
      expect(find.text('아직 나눈 이야기가 없어요'), findsOneWidget);
      expect(find.text('다듬기'), findsNothing);
      expect(find.text('이 날짜의 모든 기록 삭제'), findsNothing);
    },
  );

  testWidgets(
    'a directly opened comparison never offers adoption of a blank generated revision',
    (tester) async {
      final repo = _repository((request) async => jsonResponse({}));
      final day = DoranDay.fromJson({
        ..._emptyGeneratedDay('가상 검증: 내가 쓴 글'),
        'generated_text': ' \n ',
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: doranTheme(),
          home: DayEditor(repository: repo, day: day, compare: true),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        '가상 검증: 내가 쓴 글',
      );
      await tester.ensureVisible(find.textContaining('비교할 새 일기 내용이 없어요'));
      await tester.pumpAndSettle();
      expect(find.textContaining('비교할 새 일기 내용이 없어요'), findsOneWidget);
      expect(find.text('새 AI 일기'), findsNothing);
      expect(find.text('새 AI 일기 전체 반영'), findsNothing);
      expect(find.text('내 글 저장').hitTestable(), findsOneWidget);
    },
  );
}
