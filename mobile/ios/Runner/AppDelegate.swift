import AVFoundation
import Flutter
import UIKit
import workmanager_apple

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    // Keeps the statically linked ONNX Runtime (offline voices) in the app; see the bridging header.
    _ = OrtGetApiBase()
    // Background sync (iOS app refresh): handlers must be registered before launch finishes, and
    // the background engine needs the plugins too.
    WorkmanagerPlugin.registerLaunchHandlers()
    WorkmanagerPlugin.setPluginRegistrantCallback { registry in
      GeneratedPluginRegistrant.register(with: registry)
    }
    WorkmanagerPlugin.registerPeriodicTask(withIdentifier: "io.github.kobi2187.readaloud.sync", earliestBeginInSeconds: NSNumber(value: 60 * 60))
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
      let audio = FlutterMethodChannel(name: "readaloud/audio", binaryMessenger: registrar.messenger())
      audio.setMethodCallHandler { call, result in
        guard call.method == "encodeM4a", let args = call.arguments as? [String: String],
              let wav = args["wav"], let out = args["out"] else {
          result(FlutterMethodNotImplemented)
          return
        }
        AppDelegate.encodeQueue.async {
          let ok = AppDelegate.encodeM4a(wav: URL(fileURLWithPath: wav), out: URL(fileURLWithPath: out))
          DispatchQueue.main.async { result(ok) }
        }
      }
    }
  }

  private static let encodeQueue = DispatchQueue(label: "readaloud.audio-encoding", qos: .utility)

  /// Compresses a voiced post: WAV to AAC in an .m4a file (about 6 KB a second of speech).
  private static func encodeM4a(wav: URL, out: URL) -> Bool {
    let tmp = out.appendingPathExtension("tmp.m4a")
    do {
      let input = try AVAudioFile(forReading: wav)
      let format = input.processingFormat
      let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: format.sampleRate,
        AVNumberOfChannelsKey: Int(format.channelCount),
        AVEncoderBitRateKey: 48_000,
      ]
      try? FileManager.default.removeItem(at: tmp)
      do {
        let output = try AVAudioFile(forWriting: tmp, settings: settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8192) else { return false }
        while input.framePosition < input.length {
          try input.read(into: buffer)
          if buffer.frameLength == 0 { break }
          try output.write(from: buffer)
        }
      } // the output file is closed (and finished) here
      try? FileManager.default.removeItem(at: out)
      try FileManager.default.moveItem(at: tmp, to: out)
      return true
    } catch {
      NSLog("AAC encoding failed for \(wav.path): \(error)")
      try? FileManager.default.removeItem(at: tmp)
      return false
    }
  }
}
