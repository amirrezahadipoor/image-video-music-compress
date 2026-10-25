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

## جایگاه CI (رفع H3)

قبلاً این ماژول **هیچ جابی** نداشت و در نتیجه هیچ‌کس نمی‌دانست آیا اصلاً کامپایل
و اجرا می‌شود یا نه. الان دو چیز در CI انجام می‌شود و یک چیز عمداً نه:

| چه | کجا | چرا |
|---|---|---|
| کانفیگ شدن ماژول‌های `:benchmark` و `:baselineprofile` | جاب `build` (مرحلهٔ ۳) | اگر ماژول بشکند، همان کامیت قرمز می‌شود |
| اجرای کامل هارنس (TTID/TTFD) روی امولاتور API 33 | جاب `benchmark` (`continue-on-error: true`) | تضمین می‌کند سلکتور/API تست با گذشت زمان نپوسد؛ اعداد artifact برای روند-sizing می‌مانند |
| **اعداد منتشرشده در جدول بالا** | فقط دستگاه فیزیکی | عدد امولاتور با CPU مشترک و بدون throttling واقعی، ادعای بازاریابی را پشتیبانی نمی‌کند |

یعنی قاعدهٔ قبلی («امولاتور بی‌اعتبار است، پس هیچ جابی نباشد») حفظ شده، ولی
پیامد ناخواسته‌اش («هارنس می‌پوسد و کسی نمی‌فهمد») برداشته شده است.
خروجی هر ران در artifact با نام `benchmark-output` هست.

## چطور baseline پروفایل عکس‌ها را بسازیم (G3)

تست بصری حالا خودش عکس می‌گیرد؛ برای ساخت یا به‌روزرسانی baseline:

```bash
# ۱) ران تست روی دستگاه/امولاتور، با پرچم ثبت
./gradlew :app:connectedBazaarDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.compressly.ScreenshotRegressionTest

# ۲) کشیدن عکس‌ها از حافظهٔ برنامه
adb pull /sdcard/Android/data/ir.siliksama.hajmino.debug/files/screenshots ./shots

# ۳) بازبینی چشمی، سپس کپی به assets (نام فایل‌ها باید یکی بماند)
cp ./shots/*.png app/src/androidTest/assets/baselines/

# ۴) اجرای دوباره: حالا هر اختلاف بیش از ۳٪ رنگ روشنایی، تست را رد می‌کند
```

اگر پوشهٔ `baselines` خالی باشد (وضعیت فعلی)، تست به‌جای failure فقط skip
می‌کند و عکس‌ها را به‌عنوان artifact بالا می‌فرستد.

## وضعیت واقعی جاب `benchmark` در CI (۲۰۲۶-۱۰-۲۵)

جاب حالا هارنس را واقعاً بیلد و نصب می‌کند و تا لحظهٔ اجرای تست پیش می‌رود؛
سه مانع پشت‌سرهم برداشته شد و مانع چهارم باز است:

| مانع | وضعیت | جزئیات |
|---|---|---|
| اجرای `debug` (debuggable) به‌جای واریانت benchmark | رفع شد | build type به نام `benchmark` در ماژول هارنس؛ `isDebuggable = false` |
| `checkTestedAppObfuscation` (اپ R8 شده، هارنس نشده) | رفع شد | واریانت `benchmark` اپ برای همین واریانت minify ندارد (release دست‌نخورده) |
| `GrantPermissionRule.grant(WRITE_EXTERNAL_STORAGE)` در macrobenchmark 1.3.3 روی API 33 | رفع شد | ارتقا به dependency `benchmark-macro-junit4` **1.5.0** |
| خودِ ران: پروسه در اولین اندازه‌گیری crash می‌کرد | رفع شد | در حالت self-instrumenting مقدار `targetContext.packageName` نام خودِ هارنس است؛ تست عملاً هارنس را «اندازه می‌گرفت». حالا شناسهٔ اپ صریح داده می‌شود. |

یعنی وضعیت امروز: جاب کامل سبز است (ران `40243026628`) و هر دو تست اجرا و پاس
می‌شوند؛ جاب با `continue-on-error: true` می‌ماند تا نوسان امولاتور ران را قرمز
نکند. اعداد منتشرشده در جدول بالا همچنان باید از دستگاه فیزیکی بیاید — همان
قاعدهٔ قبلی.
