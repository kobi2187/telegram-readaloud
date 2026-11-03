import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    // Keeps the statically linked ONNX Runtime (offline voices) in the app; see the bridging header.
    _ = OrtGetApiBase()
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "ReadAloudFiles") {
      let channel = FlutterMethodChannel(name: "readaloud/files", binaryMessenger: registrar.messenger())
      channel.setMethodCallHandler { call, result in
        switch call.method {
        case "excludeFromBackup":
          // Voice models and audio can be downloaded again: keep them out of iCloud backups.
          guard let path = call.arguments as? String else {
            result(FlutterError(code: "args", message: "path expected", details: nil))
            return
          }
          do {
            try FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: true)
            var url = URL(fileURLWithPath: path, isDirectory: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
            result(true)
          } catch {
            result(FlutterError(code: "io", message: error.localizedDescription, details: nil))
          }
        default:
          result(FlutterMethodNotImplemented)
        }
      }
    }
  }
}
