import 'dart:async';

import 'package:doran_mobile/ui/login.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

Widget loginApp({required Future<void> Function() onLogin, double scale = 1}) {
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
      final pending = Completer<void>();
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
      pending.complete();
      await tester.pumpAndSettle();
      expect(start, findsOneWidget);
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
      await tester.pumpWidget(loginApp(onLogin: () async {}, scale: 2));
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
      await tester.pumpWidget(loginApp(onLogin: () async {}));
      await tester.pumpAndSettle();
      await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
      await expectLater(tester, meetsGuideline(iOSTapTargetGuideline));
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      await expectLater(tester, meetsGuideline(textContrastGuideline));
      semantics.dispose();
    },
  );
}
