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
