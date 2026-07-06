# ProGuard/R8 rules for ChuckleShare Release Build

# Keep youtubedl-android and FFmpeg JNI wrapper classes
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }

# Keep Jackson JSON serialization classes (used internally by the downloader library mapping)
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Keep models mapped from Jackson
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.** *;
}
