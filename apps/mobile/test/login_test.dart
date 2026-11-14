import 'dart:async';

import 'package:doran_mobile/ui/login.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

Widget loginApp({required Future<bool> Function() onLogin, double scale = 1}) {
  return MaterialApp(
    theme: doranTheme(),
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(
        context,
      ).copyWith(textScaler: TextScaler.linear(scale)),
      child: child!,
    ),
    home: LoginPage(onLogin: onLogin),
  );
}

void main() {
  testWidgets(
    'sign-in waits for the first invocation before allowing another',
    (tester) async {
      var calls = 0;
      final pending = Completer<bool>();
      await tester.pumpWidget(
        loginApp(
          onLogin: () {
            calls++;
            return pending.future;
          },
        ),
      );
      final start = find.text('Google로 계속하기');
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pump();
      expect(find.text('로그인 여는 중'), findsOneWidget);
      await tester.tap(find.text('로그인 여는 중'));
      await tester.pump();
      expect(calls, 1);
      pending.complete(true);
      await tester.pumpAndSettle();
      expect(start, findsOneWidget);
      expect(find.textContaining('로그인을 시작하지 못했어요'), findsNothing);
    },
  );

  testWidgets(
    'failed login offers retry without rendering raw provider errors',
    (tester) async {
      var calls = 0;
      await tester.pumpWidget(
        loginApp(
          onLogin: () async {
            calls++;
            throw Exception('private token must not be shown');
          },
        ),
      );
      final start = find.text('Google로 계속하기');
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pumpAndSettle();
      expect(find.textContaining('로그인을 시작하지 못했어요'), findsOneWidget);
      expect(find.textContaining('private token'), findsNothing);
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pumpAndSettle();
      expect(calls, 2);
    },
  );

  testWidgets(
    'sign-in and privacy actions remain usable at 200 percent text scale',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(loginApp(onLogin: () async => true, scale: 2));
      expect(tester.takeException(), isNull);
      final start = find.text('Google로 계속하기');
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pumpAndSettle();
      final privacy = find.text('기록과 개인정보 안내');
      await tester.ensureVisible(privacy);
      await tester.tap(privacy);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byType(BackButton), findsOneWidget);
    },
  );

  testWidgets(
    'sign-in screen exposes labelled and sufficiently large tap targets',
    (tester) async {
      tester.view.physicalSize = const Size(430, 932);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(loginApp(onLogin: () async => true));
      await tester.pumpAndSettle();
      await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
      await expectLater(tester, meetsGuideline(iOSTapTargetGuideline));
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      await expectLater(tester, meetsGuideline(textContrastGuideline));
      semantics.dispose();
    },
  );

  testWidgets(
    'a false launch result shows an error and a successful retry clears it',
    (tester) async {
      var calls = 0;
      await tester.pumpWidget(loginApp(onLogin: () async => ++calls > 1));
      final start = find.text('Google로 계속하기');
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pumpAndSettle();
      expect(find.textContaining('로그인을 시작하지 못했어요'), findsOneWidget);
      expect(calls, 1);
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pumpAndSettle();
      expect(calls, 2);
      expect(find.textContaining('로그인을 시작하지 못했어요'), findsNothing);
      expect(find.text('로그인 여는 중'), findsNothing);
      expect(start, findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  for (final throwsError in [false, true]) {
    testWidgets(
      'a late ${throwsError ? 'exception' : 'false result'} after disposal does not update the replacement screen',
      (tester) async {
        final pending = Completer<bool>();
        await tester.pumpWidget(loginApp(onLogin: () => pending.future));
        await tester.ensureVisible(find.text('Google로 계속하기'));
        await tester.tap(find.text('Google로 계속하기'));
        await tester.pump();
        expect(find.text('로그인 여는 중'), findsOneWidget);
        await tester.pumpWidget(
          const MaterialApp(home: Scaffold(body: Text('새 화면'))),
        );
        if (throwsError) {
          pending.completeError(StateError('private provider failure'));
        } else {
          pending.complete(false);
        }
        await tester.pumpAndSettle();
        expect(find.text('새 화면'), findsOneWidget);
        expect(find.textContaining('로그인을 시작하지 못했어요'), findsNothing);
        expect(find.textContaining('private provider failure'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
