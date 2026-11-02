# jsoup references optional re2j regex engine
-dontwarn com.google.re2j.**
# OkHttp platform-specific optional dependencies
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# ONNX Runtime (offline Hebrew voice) is reached from native code through JNI.
-keep class ai.onnxruntime.** { *; }
