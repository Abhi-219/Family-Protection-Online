# ProGuard / R8 Keep Rules for Release Build

# Preserve TensorFlow Lite JNI & C++ bindings
-keep class com.google.android.gms.tflite.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.android.gms.tflite.**

# ML Kit keep rules
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# WorkManager Worker reflection constructor preservation
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# AndroidX Core & Lifecycle
-keepclassmembers class * {
    @kotlin.jvm.JvmField <fields>;
}

# Keep app package and services unobfuscated for security scanner verification
-keep class com.abhi.familyprotection.** { *; }
-keepclassmembers class com.abhi.familyprotection.** { *; }
