# Anthropic SDK uses Jackson reflection on its model classes.
-keep class com.anthropic.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.**
-dontwarn kotlin.reflect.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**

# Optional classes referenced by the Anthropic SDK's transitive deps (OpenTelemetry, AutoValue),
# not present at runtime on Android. Safe to ignore.
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn io.opentelemetry.**
