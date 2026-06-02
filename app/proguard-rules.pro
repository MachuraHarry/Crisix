# === Crisix ProGuard / R8 Rules ===

# ---- General ----
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep the application class
-keep class com.messenger.crisix.MainActivity { *; }

# ---- Room Database ----
-keep class com.messenger.crisix.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# ---- Compose ----
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ---- BouncyCastle (Ed25519 / E2EE) ----
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.jcajce.provider.** { *; }
-keepclassmembers class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.jcajce.provider.**
-dontwarn org.bouncycastle.jsse.**

# ---- MessagePack ----
-keep class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# ---- OkHttp / Okio ----
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Coil Image Loading ----
-keep class coil.** { *; }
-dontwarn coil.**

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- ZXing QR Code ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- MLKit Barcode (Fallback) ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- Security Crypto (EncryptedSharedPreferences) ----
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ---- Navigation Compose ----
-keep class androidx.navigation.** { *; }

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Timber ----
-dontwarn org.jetbrains.annotations.**

# ---- Kotlin ----
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# ---- Keep data classes used in JSON serialization ----
-keep class com.messenger.crisix.data.Contact { *; }
-keep class com.messenger.crisix.transport.TransportType { *; }
-keep class com.messenger.crisix.transport.MessageStatus { *; }
-keep class com.messenger.crisix.crypto.HandshakeInitData { *; }
