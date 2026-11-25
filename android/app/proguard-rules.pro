# Release builds aren't minified. If you turn minification on, keep what the native code calls by name:
-keep class app.compendium.Native { *; }
-keep class app.compendium.NativeHttp { *; }
-keep class app.compendium.NativeHttp$Response { *; }
-keep interface app.compendium.ProgressListener { *; }
