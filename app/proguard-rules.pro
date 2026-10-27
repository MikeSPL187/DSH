# DSH Mobile release rules.

# kotlinx.serialization: generated serializers are looked up through companions.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dsh.core.model.**$$serializer { *; }
-keep,includedescriptorclasses class com.dsh.core.protocol.**$$serializer { *; }
-keep,includedescriptorclasses class com.dsh.core.bridge.**$$serializer { *; }

# Room generates implementations by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# OkHttp platform hooks.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Plugin SDK types are loaded by the companion APK, keep the public surface stable.
-keep public class com.deepseek.dsh.plugin.nativesdk.** { public *; }

# --- Аудит F-18: R8 включён в релизе. ---
# Нативные плагины (companion APK) загружаются parent-first через загрузчик приложения
# и линкуются с копиями SDK, Compose, AndroidX и Kotlin, которые несёт DSH, по именам.
# Эти пакеты не переименовываются и не урезаются; R8 сжимает код самого приложения
# и прочие библиотеки (OkHttp, Room, сериализация и т. д.).
-keep class com.deepseek.dsh.plugin.nativesdk.** { *; }
-keep interface com.deepseek.dsh.plugin.nativesdk.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.collection.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.coroutines.debug.**

# WebView JS-мост вызывается по имени метода.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# HostProcessRunner читает pid процесса рефлексией.
-keepclassmembers class java.lang.Process { *; }
