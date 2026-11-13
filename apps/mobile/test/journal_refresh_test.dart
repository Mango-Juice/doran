import 'dart:async';

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:doran_mobile/ui/journal.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fixtures.dart';

DoranRepository journalRepository(
  Future<http.Response> Function(http.Request) handler,
) => DoranRepository(
  client: MockClient(handler),
  token: () async => 'token',
  userId: () => 'owner',
  cache: MemoryCache(),
  baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
  publishableKey: 'test',
);

Widget journalApp(DoranRepository repository, int revision) => MaterialApp(
  theme: doranTheme(),
  home: Scaffold(
    body: JournalPage(
      key: const ValueKey('today'),
      repository: repository,
      refreshRevision: revision,
    ),
  ),
);

void main() {
  testWidgets(
    'silent refresh retains loaded history pages using the refreshed cursor',
    (tester) async {
      tester.view.physicalSize = const Size(430, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var refreshing = false;
      final requestedCursors = <String?>[];
      final repo = journalRepository((request) async {
        final cursor = request.url.queryParameters['cursor'];
        requestedCursors.add(cursor);
        final suffix = refreshing ? '최신' : '이전';
        if (cursor == null) {
          return jsonResponse({
            'items': [
              {...recordJson(text: '첫 기록 $suffix'), 'id': 'record-1'},
              {...recordJson(text: '둘째 기록 $suffix'), 'id': 'record-2'},
            ],
            'next_cursor': refreshing ? 'fresh-cursor' : 'old-cursor',
          });
        }
        expect(cursor, refreshing ? 'fresh-cursor' : 'old-cursor');
        return jsonResponse({
          'items': [
            {...recordJson(text: '추가로 읽던 기록 $suffix'), 'id': 'record-3'},
          ],
          'next_cursor': null,
        });
      });
      Widget app(int revision) => MaterialApp(
        theme: doranTheme(),
        home: Scaffold(
          body: JournalPage(
            key: const ValueKey('history'),
            repository: repo,
            history: true,
            refreshRevision: revision,
          ),
        ),
      );
      await tester.pumpWidget(app(0));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('기록 더 불러오기'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('기록 더 불러오기'));
      await tester.pumpAndSettle();
      expect(find.text('추가로 읽던 기록 이전'), findsOneWidget);
      requestedCursors.clear();
      refreshing = true;
      await tester.pumpWidget(app(1));
      await tester.pumpAndSettle();
      expect(requestedCursors, [null, 'fresh-cursor']);
      expect(find.text('추가로 읽던 기록 최신'), findsOneWidget);
      expect(find.text('기록 더 불러오기'), findsNothing);
    },
  );

  testWidgets(
    'automatic refresh preserves the date chosen in the date picker and keeps readable content',
    (tester) async {
      final today = DateTime.parse(kstDate());
      final selected = today.subtract(const Duration(days: 1));
      final selectedDate =
          '${selected.year}-${selected.month.toString().padLeft(2, '0')}-${selected.day.toString().padLeft(2, '0')}';
      final requests = <Uri>[];
      var refreshing = false;
      final pendingRecords = Completer<http.Response>();
      final repo = journalRepository((request) async {
        requests.add(request.url);
        if (request.url.path.endsWith('/records')) {
          if (refreshing) return pendingRecords.future;
          return jsonResponse({'items': [], 'next_cursor': null});
        }
        final date = request.url.pathSegments.last;
        return jsonResponse({
          ...dayJson(manualText: '$date에 읽던 내 일기'),
          'date': date,
        });
      });
      await tester.pumpWidget(journalApp(repo, 0));
      await tester.pumpAndSettle();
      await tester.tap(find.text(kstDate()));
      await tester.pumpAndSettle();
      final calendar = find.byType(CalendarDatePicker);
      final localization = MaterialLocalizations.of(tester.element(calendar));
      if (selected.month != today.month) {
        await tester.tap(find.byTooltip(localization.previousMonthTooltip));
        await tester.pumpAndSettle();
      }
      await tester.tap(
        find.descendant(of: calendar, matching: find.text('${selected.day}')),
      );
      await tester.tap(find.text(localization.okButtonLabel));
      await tester.pumpAndSettle();
      expect(find.text('$selectedDate에 읽던 내 일기'), findsOneWidget);
      requests.clear();
      refreshing = true;
      await tester.pumpWidget(journalApp(repo, 1));
      await tester.pump();
      expect(requests.single.queryParameters['date'], selectedDate);
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(find.text('$selectedDate에 읽던 내 일기'), findsOneWidget);
      pendingRecords.complete(jsonResponse({'items': [], 'next_cursor': null}));
      await tester.pumpAndSettle();
      expect(requests.last.path.endsWith('/days/$selectedDate'), isTrue);
      expect(find.text(selectedDate), findsOneWidget);
      expect(find.text('$selectedDate에 읽던 내 일기'), findsOneWidget);
    },
  );

  testWidgets(
    'authentication failure on background refresh removes previously displayed private content',
    (tester) async {
      tester.view.physicalSize = const Size(430, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var revoked = false;
      final repo = journalRepository((request) async {
        if (revoked) {
          return jsonResponse({
            'error': {'code': 'unauthenticated', 'message': '조회 권한이 만료되었습니다'},
          }, 401);
        }
        if (request.url.path.endsWith('/records')) {
          return jsonResponse({
            'items': [recordJson(text: '비공개 개별 기록')],
            'next_cursor': null,
          });
        }
        return jsonResponse({
          ...dayJson(manualText: '비공개 하루 일기'),
          'date': kstDate(),
        });
      });
      await tester.pumpWidget(journalApp(repo, 0));
      await tester.pumpAndSettle();
      expect(find.text('비공개 하루 일기'), findsOneWidget);
      expect(find.text('비공개 개별 기록'), findsOneWidget);
      revoked = true;
      await tester.pumpWidget(journalApp(repo, 1));
      await tester.pumpAndSettle();
      expect(find.text('조회 권한이 만료되었습니다'), findsOneWidget);
      expect(find.text('비공개 하루 일기'), findsNothing);
      expect(find.text('비공개 개별 기록'), findsNothing);
      expect(find.text('다듬기'), findsNothing);
    },
  );
}
