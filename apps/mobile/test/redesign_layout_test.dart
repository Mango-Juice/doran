import 'dart:io';
import 'dart:ui' as ui;

import 'package:doran_mobile/data/repository.dart';
import 'package:doran_mobile/domain/models.dart';
import 'package:doran_mobile/ui/editors.dart';
import 'package:doran_mobile/ui/journal.dart';
import 'package:doran_mobile/ui/login.dart';
import 'package:doran_mobile/ui/theme.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fixtures.dart';

// All content is synthetic. No auth session, live API, or user cache is read.
final _exampleDay = {
  ...dayJson(
    manualText:
        '[가상 사례] 발표를 앞두고 조금 긴장했지만, 하고 싶은 이야기는 분명해졌다. 차 안에서 소리 내어 설명하니 생각이 정리됐다.',
  ),
  'date': kstDate(),
  'generated_text':
      '[가상 사례] 오늘은 파란 노트 발표를 준비했다. 발표 시간은 오후 세 시에서 네 시로 바뀌었다. 예상 질문을 정리하고, 핵심 내용을 차분하게 전하기로 했다.',
};
final _exampleRecord = {
  ...recordJson(
    text: '[가상 사례] 내일 발표 시간을 오후 네 시로 정정했다. 첫 문장을 짧게 다듬고 예상 질문을 정리할 예정이다.',
  ),
  'record_date': kstDate(),
};

DoranRepository _repository() => DoranRepository(
  client: MockClient((request) async {
    if (request.url.path.contains('/days/')) return jsonResponse(_exampleDay);
    return jsonResponse({
      'items': [_exampleRecord],
      'next_cursor': null,
    });
  }),
  token: () async => 'synthetic-test-token',
  userId: () => 'synthetic-design-owner',
  cache: MemoryCache(),
  baseUrl: 'https://example.test/v1',
  publishableKey: 'synthetic',
);

Widget _app(
  Widget home, {
  double scale = 1,
  double keyboard = 0,
  Key? captureKey,
}) => MaterialApp(
  theme: doranTheme(),
  debugShowCheckedModeBanner: false,
  locale: const Locale('ko'),
  supportedLocales: const [Locale('ko'), Locale('en')],
  localizationsDelegates: GlobalMaterialLocalizations.delegates,
  builder: (context, child) => MediaQuery(
    data: MediaQuery.of(context).copyWith(
      textScaler: TextScaler.linear(scale),
      viewInsets: EdgeInsets.only(bottom: keyboard),
    ),
    child: RepaintBoundary(key: captureKey, child: child!),
  ),
  home: home,
);

void _size(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('today remains readable at narrow width and doubled text', (
    tester,
  ) async {
    _size(tester, const Size(320, 900));
    await tester.pumpWidget(
      _app(Scaffold(body: JournalPage(repository: _repository())), scale: 2),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.textContaining('발표를 앞두고'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('새 AI 일기 비교'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    expect(find.text('새 AI 일기 비교').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('record save stays reachable above an open keyboard', (
    tester,
  ) async {
    _size(tester, const Size(320, 760));
    await tester.pumpWidget(
      _app(
        RecordEditor(
          repository: _repository(),
          record: DoranRecord.fromJson(_exampleRecord),
        ),
        keyboard: 280,
      ),
    );
    await tester.pumpAndSettle();
    final save = find.widgetWithText(FilledButton, '변경 사항 저장');
    expect(save.hitTestable(), findsOneWidget);
    expect(tester.getRect(save).bottom, lessThanOrEqualTo(480));
    expect(tester.takeException(), isNull);
  });

  testWidgets('day save stays reachable with doubled text and keyboard', (
    tester,
  ) async {
    _size(tester, const Size(320, 900));
    await tester.pumpWidget(
      _app(
        DayEditor(
          repository: _repository(),
          day: DoranDay.fromJson(_exampleDay),
        ),
        scale: 2,
        keyboard: 280,
      ),
    );
    await tester.pumpAndSettle();
    final save = find.widgetWithText(FilledButton, '내 글 저장');
    expect(save.hitTestable(), findsOneWidget);
    expect(tester.getRect(save).bottom, lessThanOrEqualTo(620));
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'comparison keeps both versions and explicit adoption at narrow width',
    (tester) async {
      _size(tester, const Size(320, 900));
      await tester.pumpWidget(
        _app(
          DayEditor(
            repository: _repository(),
            day: DoranDay.fromJson(_exampleDay),
            compare: true,
          ),
          scale: 2,
        ),
      );
      await tester.pumpAndSettle();
      // Drag through the outer list's padding, so an inner text field cannot
      // consume the gesture. Check the rendered button, not just its existence.
      final list = find.byType(ListView).first;
      final action = find.text('새 AI 일기 전체 반영');
      for (
        var attempt = 0;
        attempt < 12 && action.hitTestable().evaluate().isEmpty;
        attempt++
      ) {
        await tester.dragFrom(
          tester.getTopLeft(list) + const Offset(8, 400),
          const Offset(0, -240),
        );
        await tester.pumpAndSettle();
      }
      expect(find.text('새 AI 일기 전체 반영').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('새 AI 일기 전체 반영'));
      await tester.pumpAndSettle();
      expect(find.text('새 AI 일기를 반영할까요?'), findsOneWidget);
      expect(find.text('내 글 유지'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  if (const bool.fromEnvironment('DORAN_CAPTURE_UI')) {
    testWidgets('capture production widgets with synthetic design content', (
      tester,
    ) async {
      _size(tester, const Size(430, 960));
      final suit = FontLoader('SUIT')
        ..addFont(rootBundle.load('assets/fonts/SUIT-Variable.ttf'));
      final icons = FontLoader('MaterialIcons')
        ..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
      final google = FontLoader('GoogleSans')
        ..addFont(rootBundle.load('assets/google/GoogleSans.ttf'));
      await tester.runAsync(() async {
        await suit.load();
        await google.load();
        await icons.load();
      });
      final screens = <String, Widget>{
        'today': Scaffold(
          appBar: AppBar(
            toolbarHeight: 48,
            title: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [DoranMark(size: 28), SizedBox(width: 8), Text('도란')],
            ),
          ),
          body: JournalPage(repository: _repository()),
          bottomNavigationBar: NavigationBar(
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.today_outlined),
                selectedIcon: Icon(Icons.today),
                label: '오늘',
              ),
              NavigationDestination(
                icon: Icon(Icons.menu_book_outlined),
                selectedIcon: Icon(Icons.menu_book),
                label: '기록',
              ),
              NavigationDestination(
                icon: Icon(Icons.tune_outlined),
                selectedIcon: Icon(Icons.tune),
                label: '설정',
              ),
            ],
          ),
        ),
        'comparison': DayEditor(
          repository: _repository(),
          day: DoranDay.fromJson(_exampleDay),
          compare: true,
        ),
        'login': LoginPage(onLogin: () async => true),
      };
      const selectedScreen = String.fromEnvironment('DORAN_CAPTURE_SCREEN');
      for (final entry in screens.entries) {
        if (selectedScreen.isNotEmpty && entry.key != selectedScreen) continue;
        final key = GlobalKey();
        await tester.pumpWidget(_app(entry.value, captureKey: key));
        await tester.pumpAndSettle();
        if (entry.key == 'login') {
          await tester.runAsync(
            () => precacheImage(
              const AssetImage('assets/google/g-logo.png'),
              key.currentContext!,
            ),
          );
          await tester.pumpAndSettle();
        }
        expect(tester.takeException(), isNull);
        final boundary =
            key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        await tester.runAsync(() async {
          final image = await boundary.toImage(pixelRatio: 2);
          final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
          final directory = Directory('qa/redesign');
          await directory.create(recursive: true);
          await File(
            '${directory.path}/${entry.key}.png',
          ).writeAsBytes(bytes!.buffer.asUint8List());
          image.dispose();
        });
      }
    });
  }
}
