# ML Kit GenAI talks to the AICore system service over Binder with reflection-backed
# protos. Keep it intact rather than risk a release-only crash.
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }
-dontwarn com.google.mlkit.genai.**
-dontwarn com.google.android.gms.internal.mlkit_genai_**

# MediaPipe Tasks call back into Java from JNI and parse their options as protobuf-lite.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.flogger.**
-dontwarn com.google.auto.value.**

# MediaPipe Tasks hard-wire a metrics logger that queues usage stats for upload to Google.
# The app has no INTERNET permission, so they could never be sent; this also stops them being
# recorded at all in release builds.
-assumenosideeffects class com.google.mediapipe.tasks.core.logging.RemoteLoggingClient {
    public void logEvent(...);
}

# Compile-only annotations referenced by Guava and friends.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.lang.model.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.google.common.**
-dontwarn module-info

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep crash stack traces readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
