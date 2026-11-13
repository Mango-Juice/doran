import 'dart:async';

import 'package:doran_mobile/startup.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const readyChild = Scaffold(body: Text('기록 화면 준비 완료'));

Widget bootstrapApp(
  AppInitialization initialization, {
  Duration threshold = const Duration(seconds: 10),
}) => MaterialApp(
  home: AppBootstrap(
    initialization: initialization,
    waitThreshold: threshold,
    child: readyChild,
  ),
);

void main() {
  group('AppInitialization lifecycle', () {
    test(
      'concurrent ensureReady calls share one initialization future',
      () async {
        final pending = Completer<void>();
        var initialized = 0, cleaned = 0;
        final initialization = AppInitialization(
          initialize: () {
            initialized++;
            return pending.future;
          },
          cleanupAfterFailure: () async {
            cleaned++;
          },
        );
        final first = initialization.ensureReady();
        final second = initialization.ensureReady();
        expect(identical(first, second), isTrue);
        expect(initialization.canRetry, isFalse);
        await Future<void>.delayed(Duration.zero);
        expect(initialized, 1);
        pending.complete();
        await first;
        await initialization.ensureReady();
        expect(initialized, 1);
        expect(cleaned, 0);
        expect(initialization.canRetry, isFalse);
      },
    );

    test(
      'retry while initialization is pending never starts a second operation',
      () async {
        final pending = Completer<void>();
        var initialized = 0;
        final initialization = AppInitialization(
          initialize: () {
            initialized++;
            return pending.future;
          },
          cleanupAfterFailure: () async {},
        );
        final first = initialization.ensureReady();
        final repeated = initialization.retry();
        expect(identical(first, repeated), isTrue);
        await Future<void>.delayed(Duration.zero);
        expect(initialized, 1);
        pending.complete();
        await Future.wait([first, repeated]);
      },
    );

    test(
      'cleanup must finish before failed initialization becomes retryable',
      () async {
        final cleanupStarted = Completer<void>();
        final cleanupDone = Completer<void>();
        var initialized = 0;
        final order = <String>[];
        final initialization = AppInitialization(
          initialize: () async {
            initialized++;
            order.add('initialize-$initialized');
            if (initialized == 1) {
              throw StateError('first initialization failed');
            }
          },
          cleanupAfterFailure: () async {
            order.add('cleanup-start');
            cleanupStarted.complete();
            await cleanupDone.future;
            order.add('cleanup-done');
          },
        );
        final first = initialization.ensureReady();
        final failure = expectLater(
          first,
          throwsA(
            isA<StartupFailure>().having(
              (error) => error.retryable,
              'retryable',
              isTrue,
            ),
          ),
        );
        await cleanupStarted.future;
        expect(initialization.canRetry, isFalse);
        final duringCleanup = initialization.retry();
        expect(identical(first, duringCleanup), isTrue);
        expect(initialized, 1);
        cleanupDone.complete();
        await failure;
        expect(initialization.canRetry, isTrue);
        await initialization.retry();
        expect(order, [
          'initialize-1',
          'cleanup-start',
          'cleanup-done',
          'initialize-2',
        ]);
        expect(initialization.canRetry, isFalse);
      },
    );

    test('cleanup failure forbids a second initialization attempt', () async {
      var initialized = 0, cleaned = 0;
      final initialization = AppInitialization(
        initialize: () async {
          initialized++;
          throw StateError('initialization failed');
        },
        cleanupAfterFailure: () async {
          cleaned++;
          throw StateError('cleanup failed');
        },
      );
      await expectLater(initialization.ensureReady(), throwsA(anything));
      expect(initialization.canRetry, isFalse);
      await expectLater(initialization.retry(), throwsA(anything));
      expect(initialized, 1);
      expect(cleaned, 1);
    });
  });

  group('AppBootstrap user experience', () {
    testWidgets(
      'the preparation view exists before initialization starts and success reveals child',
      (tester) async {
        final pending = Completer<void>();
        var loadingViewObserved = false;
        final initialization = AppInitialization(
          initialize: () {
            loadingViewObserved = find
                .text('도란을 준비하고 있어요')
                .evaluate()
                .isNotEmpty;
            return pending.future;
          },
          cleanupAfterFailure: () async {},
        );
        await tester.pumpWidget(bootstrapApp(initialization));
        await tester.pump();
        expect(loadingViewObserved, isTrue);
        expect(find.text('도란을 준비하고 있어요'), findsOneWidget);
        expect(find.text('기록 화면 준비 완료'), findsNothing);
        pending.complete();
        await tester.pumpAndSettle();
        expect(find.text('기록 화면 준비 완료'), findsOneWidget);
      },
    );

    testWidgets(
      'a slow startup recheck observes the existing operation rather than initializing again',
      (tester) async {
        final pending = Completer<void>();
        var initialized = 0;
        final initialization = AppInitialization(
          initialize: () {
            initialized++;
            return pending.future;
          },
          cleanupAfterFailure: () async {},
        );
        await tester.pumpWidget(
          bootstrapApp(initialization, threshold: const Duration(seconds: 1)),
        );
        await tester.pump(const Duration(seconds: 2));
        expect(find.text('준비가 오래 걸리고 있어요'), findsOneWidget);
        expect(find.text('다시 확인'), findsOneWidget);
        await tester.tap(find.text('다시 확인'));
        await tester.pump(const Duration(seconds: 2));
        await tester.tap(find.text('다시 확인'));
        await tester.pump();
        expect(initialized, 1);
        expect(initialization.canRetry, isFalse);
        pending.complete();
        await tester.pumpAndSettle();
        expect(find.text('기록 화면 준비 완료'), findsOneWidget);
      },
    );

    testWidgets(
      'a recoverable startup failure exposes safe copy and retries only after cleanup',
      (tester) async {
        var initialized = 0, cleaned = 0;
        final initialization = AppInitialization(
          initialize: () async {
            initialized++;
            if (initialized == 1) {
              throw StateError('private-provider-response-sk-test-value');
            }
          },
          cleanupAfterFailure: () async {
            cleaned++;
          },
        );
        await tester.pumpWidget(bootstrapApp(initialization));
        await tester.pumpAndSettle();
        expect(find.text('앱을 준비하지 못했어요'), findsOneWidget);
        expect(find.textContaining('private-provider-response'), findsNothing);
        expect(find.textContaining('sk-test-value'), findsNothing);
        expect(cleaned, 1);
        await tester.tap(find.text('다시 시도'));
        await tester.pumpAndSettle();
        expect(initialized, 2);
        expect(cleaned, 1);
        expect(find.text('기록 화면 준비 완료'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets(
      'unsafe cleanup failure requires reopening the app without a retry action',
      (tester) async {
        var initialized = 0;
        final initialization = AppInitialization(
          initialize: () async {
            initialized++;
            throw StateError('raw-initialization-error');
          },
          cleanupAfterFailure: () async {
            throw StateError('raw-cleanup-error');
          },
        );
        await tester.pumpWidget(bootstrapApp(initialization));
        await tester.pumpAndSettle();
        expect(find.text('앱을 다시 열어 주세요'), findsOneWidget);
        expect(find.text('다시 시도'), findsNothing);
        expect(find.text('다시 확인'), findsNothing);
        expect(find.textContaining('raw-'), findsNothing);
        expect(initialized, 1);
        expect(tester.takeException(), isNull);
      },
    );

    for (final fails in [false, true]) {
      testWidgets(
        'late ${fails ? 'failure' : 'success'} after disposal cannot replace the new screen',
        (tester) async {
          final pending = Completer<void>();
          final initialization = AppInitialization(
            initialize: () => pending.future,
            cleanupAfterFailure: () async {},
          );
          await tester.pumpWidget(bootstrapApp(initialization));
          await tester.pump();
          await tester.pumpWidget(
            const MaterialApp(home: Scaffold(body: Text('다른 화면'))),
          );
          if (fails) {
            pending.completeError(StateError('late-provider-failure'));
          } else {
            pending.complete();
          }
          await tester.pumpAndSettle();
          expect(find.text('다른 화면'), findsOneWidget);
          expect(find.text('기록 화면 준비 완료'), findsNothing);
          expect(find.text('앱을 준비하지 못했어요'), findsNothing);
          expect(tester.takeException(), isNull);
        },
      );
    }
  });
}
