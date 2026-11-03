#import "GeneratedPluginRegistrant.h"

// ONNX Runtime is linked statically (the onnxruntime-c pod) and reached from Dart through
// DynamicLibrary.process(); referencing its entry point keeps it in the binary.
struct OrtApiBase;
const struct OrtApiBase *OrtGetApiBase(void);
