# OkHttp ships its own consumer ProGuard rules; these only silence optional-dep warnings
# for TLS providers OkHttp references reflectively but that aren't present on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# JNI reads these classes and fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
