# Chaquopy ships a Python runtime reached through JNI; keep its entry points.
-keep class com.chaquo.python.** { *; }

# LiteRT-LM and ONNX Runtime are JNI-backed.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class ai.onnxruntime.** { *; }

-dontwarn com.google.ai.edge.litertlm.**
-dontwarn ai.onnxruntime.**
