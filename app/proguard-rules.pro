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
# SEC-7 fix: narrowed from -keep class io.ktor.** { *; } to only keep
# what's actually needed — service loader entries for engine discovery.
# The blanket keep was defeating R8's tree-shaking.
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.client.plugins.**$Companion { *; }
-keepnames class io.ktor.client.plugins.sse.** { *; }
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
# SEC-7 fix: narrowed from -keep class androidx.compose.** { *; }
# Compose compiler plugin already emits necessary keep rules.
# Only keep runtime + material3 internals that use reflection.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.material3.** { *; }
-dontwarn androidx.compose.**

# --- App -------------------------------------------------------------------
# Keep entry points and reflection-accessed models.
-keep class dev.hermes.hermex.MainActivity { *; }
-keep class dev.hermes.core.** { *; }
-keep class dev.hermes.core.network.ChatStream$* { *; }
-keep @kotlinx.serialization.Serializable class dev.hermes.** { *; }

# --- Security / crypto ------------------------------------------------------
# androidx.security:security-crypto pulls in Google Tink, which references
# errorprone annotations that aren't on the runtime classpath. R8 raises
# "Missing class" errors without these suppressions.
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**

# --- Markdown renderer (Mike Penz) ------------------------------------------
# The markdown renderer uses reflection to load extended components.
-keep class com.mikepenz.markdown.** { *; }
-dontwarn com.mikepenz.markdown.**

# --- R8 optimizations -------------------------------------------------------
# Re-package classes to shrink method count and hinder reverse engineering.
# Disable for now to avoid breaking reflection-heavy libs; enable once
# runtime smoke-tests pass.
# -allowaccessmodification
# -repackageclasses ''
