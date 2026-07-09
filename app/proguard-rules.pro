# Keep libausbc / native UVC classes (JNI-referenced). Debug builds don't minify,
# but this keeps a release build from stripping the native bridge classes.
-keep class com.jiangdg.** { *; }
-keep class com.serenegiant.** { *; }
