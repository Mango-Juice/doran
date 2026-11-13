import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/ui/pairing.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fixtures.dart';

DoranRepository pairingRepository(
  Future<http.Response> Function(http.Request) handler,
) => DoranRepository(
  client: MockClient(handler),
  token: () async => 'token',
  userId: () => 'owner',
  cache: MemoryCache(),
  baseUrl: 'https://example.supabase.co/functions/v1/doran-api/v1',
  publishableKey: 'test',
);

void main() {
  testWidgets(
    'pairing submission stays disabled until exactly six digits are entered',
    (tester) async {
      var calls = 0;
      final repo = pairingRepository((_) async {
        calls++;
        return jsonResponse({});
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: doranTheme(),
          home: PairingPage(repository: repo),
        ),
      );
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '연결 요청'))
            .onPressed,
        isNull,
      );
      await tester.enterText(find.byType(TextField), '01234');
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '연결 요청'))
            .onPressed,
        isNull,
      );
      await tester.enterText(find.byType(TextField), '012345');
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '연결 요청'))
            .onPressed,
        isNotNull,
      );
      expect(calls, 0);
    },
  );

  testWidgets(
    'mobile approval alone never reports paired before the vehicle confirms',
    (tester) async {
      var status = 'claimed';
      final expiry = DateTime.now()
          .toUtc()
          .add(const Duration(minutes: 5))
          .toIso8601String();
      final repo = pairingRepository((request) async {
        if (request.method == 'POST') {
          return jsonResponse({
            'id': 'pair-1',
            'expires_at': expiry,
            'status': 'claimed',
            'device_label': '도란 차량',
          });
        }
        return jsonResponse({
          'id': 'pair-1',
          'expires_at': expiry,
          'status': status,
        });
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: doranTheme(),
          home: PairingPage(repository: repo),
        ),
      );
      await tester.enterText(find.byType(TextField), '012345');
      await tester.pump();
      await tester.ensureVisible(find.text('연결 요청'));
      await tester.tap(find.text('연결 요청'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('차량 화면에서 연결을 확인해 주세요'), findsOneWidget);
      expect(find.text('차량과 연결했어요'), findsNothing);
      status = 'paired';
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('차량과 연결했어요'), findsOneWidget);
      expect(find.text('완료'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('expired pairing can restart with a fresh empty code', (
    tester,
  ) async {
    final expiry = DateTime.now()
        .toUtc()
        .add(const Duration(minutes: 5))
        .toIso8601String();
    final repo = pairingRepository(
      (request) async => request.method == 'POST'
          ? jsonResponse({
              'id': 'pair-1',
              'expires_at': expiry,
              'status': 'claimed',
              'device_label': '도란 차량',
            })
          : jsonResponse({
              'id': 'pair-1',
              'expires_at': expiry,
              'status': 'expired',
            }),
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: doranTheme(),
        home: PairingPage(repository: repo),
      ),
    );
    await tester.enterText(find.byType(TextField), '012345');
    await tester.pump();
    await tester.ensureVisible(find.text('연결 요청'));
    await tester.tap(find.text('연결 요청'));
    await tester.pumpAndSettle();
    expect(find.text('연결 코드가 만료됐어요'), findsOneWidget);
    await tester.tap(find.text('새 코드 입력'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(find.byType(TextField)).controller!.text,
      isEmpty,
    );
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '연결 요청'))
          .onPressed,
      isNull,
    );
    await tester.pumpWidget(const SizedBox());
  });
}
