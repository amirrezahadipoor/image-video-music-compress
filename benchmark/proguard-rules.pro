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
