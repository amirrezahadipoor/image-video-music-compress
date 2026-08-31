# ---- Compressly ProGuard rules -------------------------------------------

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Room (entities/DAOs are accessed via generated code)
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**

# jump3r (pure-Java MP3 encoder). We only use the low-level LAME port
# (mp3.* and mpg.* packages); keeping everything would drag in the
# javax.sound-dependent LameEncoder wrapper, which Android lacks.
-keep class de.sciss.jump3r.mp3.** { *; }
-keep class de.sciss.jump3r.mpg.** { *; }
-dontwarn de.sciss.jump3r.**

# jaudiotagger (offline metadata tagging)
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.sound.**

# Kotlinx coroutines
-dontwarn kotlinx.coroutines.**

# MediaCodec / MediaExtractor / MediaMuxer / MediaMetadataRetriever are framework
# classes; nothing to keep.

# Adivery specific rules
-keep class com.adivery.** { *; }
-dontwarn com.adivery.**
-dontwarn com.mbridge.**
-dontwarn com.google.android.gms.ads.**
-dontwarn com.google.android.gms.common.**

# Poolakey (Cafe Bazaar IAP SDK)
-keep class ir.cafebazaar.poolakey.** { *; }
-dontwarn ir.cafebazaar.poolakey.**

# Keep billing interfaces for reflection (AppContainer constructs Poolakey by name)
-keep interface com.compressly.core.billing.** { *; }
-keep class com.compressly.core.billing.** { *; }
-keepclassmembers class com.compressly.core.billing.PoolakeyBillingManager {
    public <init>(kotlin.jvm.functions.Function2);
}

# Ads.create() resolves the flavor's provider by NAME via Class.forName, so R8
# must not rename it. Without this the bazaar release silently fell back to
# NoopAdsProvider and no ad was ever shown.
-keep class com.compressly.core.ads.** { *; }
-keepclassmembers class * implements com.compressly.core.ads.AdsProvider { <init>(); }
