# The benchmark harness is only ever launched reflectively by the test
# instrumentation (AGP walks the test APK for @Test classes), so R8 has to keep
# the entry points. Everything else in the test APK can be shrunk normally.
-keep class ir.siliksama.hajmino.benchmark.** { *; }
-keepclassmembers class * {
    @org.junit.Test <methods>;
}
# macrobenchmark reflects on its own rule/scope classes and the profiler.
-keep class androidx.benchmark.** { *; }
-keep class androidx.test.uiautomator.** { *; }
-keepattributes *Annotation*, Signature, Exceptions, InnerClasses

# The harness APK only has to be shrink-compatible with the app APK (AGP's
# checkTestedAppObfuscation requires both sides to be shrunk). It pulls in
# androidx.lifecycle/test leftovers whose optional desktop-only classes are
# absent from the test classpath (e.g. ArchTaskExecutor) — missing classes in a
# *test* APK are harmless, they are never loaded on-device.
-dontwarn **
