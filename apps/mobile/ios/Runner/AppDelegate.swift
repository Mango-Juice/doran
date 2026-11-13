import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    #if DEBUG
    NSLog("[doran-startup] swift.launch ms=%lld", Int64(Date().timeIntervalSince1970 * 1000))
    #endif
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    #if DEBUG
    NSLog("[doran-startup] swift.plugins.begin ms=%lld", Int64(Date().timeIntervalSince1970 * 1000))
    #endif
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    #if DEBUG
    NSLog("[doran-startup] swift.plugins.complete ms=%lld", Int64(Date().timeIntervalSince1970 * 1000))
    #endif
  }
}
