import 'package:flutter/foundation.dart';

enum StartupStage {
  dartMain,
  bootstrapFrameBuilt,
  firstFrameRasterized,
  supabaseBegin,
  sessionReadBegin,
  sessionReadComplete,
  supabaseComplete,
  supabaseFailure,
  cleanupBegin,
  cleanupComplete,
  cleanupFailure,
  uiReady,
}

/// Diagnostic builds record only a fixed stage name and timestamp. Never keys,
/// session contents, account identifiers, or exception messages.
void traceStartup(StartupStage stage) {
  if (kDebugMode) {
    debugPrint(
      '[doran-startup] ${stage.name} ms=${DateTime.now().millisecondsSinceEpoch}',
    );
  }
}
