# --- ML Kit (on-device) ---
# The ML Kit AARs ship their own consumer keep rules; these are extra safety nets.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- Kotlin coroutines (play-services Task adapter) ---
-dontwarn kotlinx.coroutines.**

# Google Play services optional APIs referenced reflectively by ML Kit on some devices
-dontwarn com.google.android.gms.**

# Keep stack traces readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
