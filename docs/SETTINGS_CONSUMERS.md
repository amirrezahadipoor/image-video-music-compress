# هر کلید تنظیم → کدام مصرف می‌کند

این جدول ضدتکرار «قابلیتی که UI دارد و موتور مصرف نمی‌کند» (و برعکس: موتوری که
قابلیت دارد و هیچ UI‌ای آن را تنظیم نمی‌کند) است. هر سطر از کد تأیید شده است؛
اگر سطری عوض شد، همین‌جا هم عوض شود — وگرنه دوباره همان باگ برمی‌گردد.
ستون «دروازه» یعنی این مسیر در CI هم سنجیده می‌شود؛ «دستی» یعنی فقط روی دستگاه.

| کلید تنظیم | مصرف‌کنندهٔ واقعی | مسیر | دروازه |
|---|---|---|---|
| `AudioSettings.format` (AAC/MP3) | `AudioCompressor` انتخاب مسیر: Muxer AAC یا LAME | `Mp3Writer` / `MediaMuxer` | دستی |
| `AudioSettings.bitrate` (kbps) | `AudioPlanner.targetBitrateKbps(bitrate, sourceBitrate)` → هر دو مسیر + `SizeEstimator` | `core/engine/audio/*`, `estimate/SizeEstimator.kt` | `AudioPlannerTest` |
| `AudioSettings.bitrateMode` (VBR/CBR) | `Mp3Writer` (LAME -V در برابر CBR) و `SizeEstimator` (`vbr = …`) | `Mp3Writer.kt:73`, `SizeEstimator` مسیر صدا | `Mp3WriterTest` |
| `AudioSettings.preserveMetadata` | کپی تگ‌ها با jaudiotagger در انتهای مسیر MP3 | `AudioCompressor` | دستی |
| `PhotoSettings.outputFormat` | انکودر + MIME خروجی؛ در `replaceOriginal` تعیین‌کنندهٔ مسیر جایگزینی است (`decideInPlace(srcMime, outMime)`: همان فرمت ← نوشتن درجا، فرمت دیگر ← `retypeMediaRow` و بعد نوشتن درجا، MIME ناشناخته ← publish + حذف) | `Compressor.compressPhoto`, `Compressor.publishOrKeepOriginal` | `CompressionEngineTest` |
| `PhotoSettings.quality` | `PhotoCompressor` (نردبان کیفیت؛ `lastQualityUsed` به خلاصهٔ نتیجه می‌رود) | `photo/PhotoCompressor.kt` | `CompressionEngineTest` |
| `PhotoSettings.resize` / `customMaxWidth` | سقف پهنا در `PhotoCompressor` (clamp 320..8000 در UI، 64..8000 در موتور) | `photo/PhotoCompressor.kt` | `SmartSettingsTest` |
| `PhotoSettings.smart` | `SmartPhotoAdvisor` + `GradeAdvisor` (تطبیقی ۸۵→۶۵) | `photo/*` | `SmartPhotoAdvisorTest` |
| `VideoSettings.resolution` | `VideoPlanner.outputDims` → `Plan.width/height` → `MediaCodec.configure` | `VideoPlanner.kt` → `outputDims`, `MediaCodecTranscoder.kt:123` | `VideoPlannerTest` |
| `VideoResolution.CUSTOM` + `customWidth/customHeight` | عدد کاربر **سقف** است: از `encoderSize` (۶۴..۸۰۰۰، زوج) رد می‌شود، بعد به ضریب مقیاس یکسان تبدیل می‌شود تا نسبت تصویر حفظ شود و یال‌ها به مضرب ۱۶ هم‌تراز پایین می‌روند (۶۴۱×۳۶۱ روی منبع ۱۹۲۰×۱۰۸۰ ← ۶۴۰×۳۵۲) | `VideoPlanner.encoderSize` | `ResultMathTest` |
| `VideoSettings.bitrate` | `targetVideoBitrate`: دستی بر خودکار مقدم است، اما `sizeTargetMb` بر آن مقدم است | `VideoPlanner.kt` → `targetVideoBitrate` | `VideoPlannerTest` |
| `VideoSettings.sizeTargetMb` | `sizeTargetBitrate` + حلقهٔ اصلاحی `aggressiveCorrection` | `VideoPlanner.plan`, `MediaCodecTranscoder` | `SmartPlanningTest` |
| `VideoSettings.frameRate` | `resolvedFps` → `KEY_FRAME_RATE` و قیمت‌گذاری بیت‌ریت | `VideoPlanner`, transcoder | `VideoPlannerTest` |
| `VideoSettings.codec` | انتخاب encoder؛ نبودِ HW → fallback به H.264 که در UI اعلام می‌شود (`h265FellBack`) | `MediaCodecTranscoder`, `video/CodecSupport.kt` | `CompressionEngineTest` |
| `VideoSettings.audioMode` | KEEP/COMPRESS/STRIP روی ترک صدا در remux | `MediaCodecTranscoder` | دستی |
| `VideoSettings.trim*` | پنجرهٔ زمانی انکود | `MediaCodecTranscoder` | دستی |
| `VideoSettings.preserveHdr` | `KEY_COLOR_TRANSFER/STANDARD/RANGE` روی MediaFormat (با retry به فرمت حداقلی) | `MediaCodecTranscoder.kt:806-817` | دستی |
| `CompressionPreset` (SMART/BALANCED/HIGH/MAX) | `PresetDefaults.videoSettingsFor/photoSettingsFor` + `SizeEstimator` + `GradeAdvisor` | `model/PresetDefaults.kt` | `PresetDefaultsTest` |
| `outputLocation` (DEFAULT/SAME_AS_SOURCE/CUSTOM) | `OutputStore.publishTempFileDetailed`؛ اگر پوشه ممکن نشد، به پیش‌فرض می‌افتد و **در خلاصهٔ نتیجه اعلام می‌شود** | `data/OutputStore.kt` | `ResultMathTest` (نام/پسوند) |
| `replaceOriginal` | `Compressor.publishOrKeepOriginal` → `OutputStore.replaceInPlace` (اول) و در غیر این صورت publish + `delete`؛ عدم حذف → نشانة `original_retained` روی رکورد. دروازهٔ اجازه در `CompressionSettingsScreen.requestCompression` است و `MediaStoreConsent.plan` تعیین می‌کند برای کدام ردیف grantِ نوشتن و برای کدام grantِ حذف لازم باشد (تطابق این دوتایی با `decideInPlace` در `ReplaceOriginalPolicyTest` قفل شده) تاریخچه | `engine/Compressor.kt`, `core/service/JobCoordinator.kt`, `ui/screens/ResultScreen.kt` | `ResultMathTest` |

## دو قاعده‌ای که این جدول نگه داشته است
1. **هر کلید باید مصرف‌کننده‌اش را در همین جدول داشته باشد.** اگر چیزی اضافه کردید
   و ستون «مصرف‌کننده» خالی ماند، یعنی آن کلید فقط ظاهر است — همان حالتی که
   `bitrateMode`، `VideoSettings.bitrate` و `VideoResolution.CUSTOM` ماه‌ها داشتند.
2. **هیچ شکستی بی‌صدا نباشد.** `delete` بولین برمی‌گرداند، publish می‌گوید پوشه
   اعمال شد یا نه، و `replaceInPlace` خروجی ۰بایت را «موفق» گزارش نمی‌کند.
