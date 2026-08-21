# ---- Compressly ProGuard rules -------------------------------------------

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Room (entities/DAOs are accessed via generated code)
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**

# jump3r (pure-Java MP3 encoder). Internal classes are wired by name.
-keep class de.sciss.jump3r.** { *; }
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
