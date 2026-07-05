# ============================================================================
# Hermex app — ProGuard / R8 rules
# ============================================================================
# This file complements the AGP default `proguard-android-optimize.txt` and is
# applied to release builds (isMinifyEnabled = true).
# ----------------------------------------------------------------------------

# --- Kotlin metadata & reflection -------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Keep Kotlinx coroutines internals used by Room & Flow
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- Kotlinx Serialization --------------------------------------------------
# Keep the @Serializable companion serializers so they are not stripped.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn kotlinx.serialization.**

# --- Ktor (3.x) -------------------------------------------------------------
# Ktor uses reflection & service loaders for engine discovery.
-keep class io.ktor.** { *; }
-keepnames class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.LoggerFactory

# OkHttp engine
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.platform.** { *; }

# --- Room -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# --- Jetpack Compose --------------------------------------------------------
# Compose runtime keeps its own metadata; the compiler plugin already emits
# the necessary keep rules. We disable aggressive optimization on lambda
# classes to avoid crashes when navigating between screens.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- App -------------------------------------------------------------------
# Keep entry points and reflection-accessed models.
-keep class dev.hermes.hermex.MainActivity { *; }
-keep class dev.hermes.core.** { *; }
-keep class dev.hermes.core.network.ChatStream$* { *; }
-keep @kotlinx.serialization.Serializable class dev.hermes.** { *; }

# --- Security / crypto ------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# --- R8 optimizations -------------------------------------------------------
# Re-package classes to shrink method count and hinder reverse engineering.
# Disable for now to avoid breaking reflection-heavy libs; enable once
# runtime smoke-tests pass.
# -allowaccessmodification
# -repackageclasses ''
