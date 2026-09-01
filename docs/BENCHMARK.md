# اندازه‌گیری کلد استارت (TTID/TTFD) — Macrobenchmark

این سند روش دقیق ثبت اعداد استارت سرد است. اعداد واقعی فقط روی **دستگاه فیزیکی** (ترجیحاً یک گوشی رایج بازار، مثلاً شیائومی میان‌رده) معتبرند؛ امولاتور به‌دلیل CPU مشترک اعداد قابل‌اتکا نمی‌دهد.

## چرا

ادعای «۲۰ تا ۳۰ درصد استارت سریع‌تر» با Baseline Profile یک ادعای عمومی گوگل است؛ برای این اپ باید با همان دستگاه اندازه‌گیری و مستند شود.

## چگونه

پیش‌نیاز: دستگاه (Android 8.0+) با USB debugging روشن و صفحه فعال (وای‌فای/شبکه پایدار؛ حالت ذخیره انرژی بسته).

```bash
# ۱) اندازه‌گیری بدون پروفایل
./gradlew :benchmark:connectedBazaarDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ir.siliksama.hajmino.benchmark.StartupBenchmarks#startupWithoutProfile

# ۲) اندازه‌گیری با پروفایل (همان جلسه، همان حالت دستگاه)
./gradlew :benchmark:connectedBazaarDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ir.siliksama.hajmino.benchmark.StartupBenchmarks#startupWithBaselineProfile
```

نتایج (StartupTimingMetric: timeToInitialDisplayMs و timeToFullDisplayMs) در logcat با برچسب
`Macrobenchmark` و در گزارش‌های `benchmark/build/outputs/` چاپ می‌شوند.

نکته: تست `startupWithBaselineProfile` از `BaselineProfileMode.Require` استفاده می‌کند —
یعنی اگر APK اندازه‌گیری‌شده پروفایل نداشته باشد با خطا متوقف می‌شود (بیلید `benchmark`
از release می‌آید و پروفایل دارد؛ debug ندارد).

## جدول ثبت (پس از اندازه‌گیری دقیق پر شود)

| دستگاه / Android | حالت | TTID میانه (ms) | TTFD میانه (ms) | بهبود TTID | بهبود TTFD |
|---|---|---|---|---|---|
| (نمونه: Redmi Note 12 / Android 13) | بدون پروفایل | — | — | — | — |
| (نمونه: Redmi Note 12 / Android 13) | با پروفایل | — | — | — | — |

قواعد ثبت (برای اینکه اعداد قابل مقایسه باشند):

- هر دو حالت در **یک جلسه** و بدون reboot میانی؛ قبل هر تست `pressHome` و چند ثانیه سکون.
- `iterations = 5` (پیش‌فرض همین ماژول) و **میانه** گزارش شود، نه میانگین.
- بیلیکد و پوسته لانچر حین اندازه‌گیری تغییر نکند.
- پس از اعداد، تاریخ، مدل دقیق و نسخه اندروید را در همان ردیف بنویسید.

## هیچ ردی در CI

اجرای Macrobenchmark روی امولاتور CI هم زمان‌بر است و هم بی‌اعتبار؛ به‌همین دلیل
این ماژول عمداً **جاب CI ندارد** و فقط روی دستگاه واقعی اجرا می‌شود.
