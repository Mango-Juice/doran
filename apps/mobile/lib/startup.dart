import 'dart:async';
import 'package:material_ui/material_ui.dart';
import 'startup_trace.dart';
import 'ui/theme.dart';

class StartupFailure implements Exception {
  final bool retryable;
  const StartupFailure({required this.retryable});
}

/// Owns a process-level initialization attempt. A visual deadline never cancels
/// the native operation, so waiting again must reuse its Future.
class AppInitialization {
  final Future<void> Function() initialize;
  final Future<void> Function() cleanupAfterFailure;
  Future<void>? _attempt;
  bool _canRetry = false;

  AppInitialization({
    required this.initialize,
    required this.cleanupAfterFailure,
  });

  bool get canRetry => _canRetry;
  Future<void> ensureReady() => _attempt ??= _initializeOnce();

  Future<void> retry() {
    if (_canRetry) {
      _canRetry = false;
      _attempt = null;
    }
    return ensureReady();
  }

  Future<void> _initializeOnce() async {
    try {
      await initialize();
    } catch (_) {
      try {
        await cleanupAfterFailure();
      } catch (_) {
        throw const StartupFailure(retryable: false);
      }
      _canRetry = true;
      throw const StartupFailure(retryable: true);
    }
  }
}

class AppBootstrap extends StatefulWidget {
  final AppInitialization initialization;
  final Widget child;
  final Duration waitThreshold;
  const AppBootstrap({
    super.key,
    required this.initialization,
    required this.child,
    this.waitThreshold = const Duration(seconds: 10),
  });

  @override
  State<AppBootstrap> createState() => _AppBootstrapState();
}

class _AppBootstrapState extends State<AppBootstrap> {
  Timer? _deadline;
  int _observation = 0;
  bool _ready = false;
  bool _slow = false;
  StartupFailure? _failure;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      traceStartup(StartupStage.bootstrapFrameBuilt);
      unawaited(_observe(widget.initialization.ensureReady()));
    });
  }

  Future<void> _observe(Future<void> attempt) async {
    final observation = ++_observation;
    _deadline?.cancel();
    setState(() {
      _slow = false;
      _failure = null;
    });
    _deadline = Timer(widget.waitThreshold, () {
      if (mounted && observation == _observation) {
        setState(() => _slow = true);
      }
    });
    try {
      await attempt;
      if (!mounted || observation != _observation) return;
      traceStartup(StartupStage.uiReady);
      setState(() => _ready = true);
    } catch (error) {
      if (!mounted || observation != _observation) return;
      setState(() {
        _failure = error is StartupFailure
            ? error
            : const StartupFailure(retryable: false);
      });
    } finally {
      if (observation == _observation) _deadline?.cancel();
    }
  }

  @override
  void dispose() {
    ++_observation;
    _deadline?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_ready) return widget.child;
    final failed = _failure != null;
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '도란',
      theme: doranTheme(),
      locale: const Locale('ko'),
      supportedLocales: const [Locale('ko'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Scaffold(
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(32),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 440),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('도란', style: TextStyle(fontSize: 24)),
                    const SizedBox(height: 32),
                    if (!failed && !_slow)
                      const CircularProgressIndicator()
                    else
                      const Icon(Icons.info_outline, size: 32),
                    const SizedBox(height: 24),
                    Text(
                      failed
                          ? _failure!.retryable
                                ? '앱을 준비하지 못했어요'
                                : '앱을 다시 열어 주세요'
                          : _slow
                          ? '준비가 오래 걸리고 있어요'
                          : '도란을 준비하고 있어요',
                      style: const TextStyle(fontSize: 22, height: 1.4),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      failed
                          ? _failure!.retryable
                                ? '연결을 확인하고 다시 시도해 주세요.'
                                : '준비를 마치지 못했어요. 앱을 닫았다가 다시 열어 주세요.'
                          : _slow
                          ? '잠시 뒤 다시 확인해 주세요. 계속 늦어지면 앱을 닫았다가 다시 열어 주세요.'
                          : '계정을 확인하고 기록을 준비하고 있어요.',
                    ),
                    if (_slow && !failed || _failure?.retryable == true) ...[
                      const SizedBox(height: 24),
                      OutlinedButton(
                        onPressed: () => unawaited(
                          _observe(
                            failed
                                ? widget.initialization.retry()
                                : widget.initialization.ensureReady(),
                          ),
                        ),
                        child: Text(failed ? '다시 시도' : '다시 확인'),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
