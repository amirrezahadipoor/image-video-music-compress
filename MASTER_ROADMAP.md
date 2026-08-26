# 🗺️ نقشه راه جامع — کاوش ریزبینانه نهایی Round 14
**تاریخ**: ۲۶ مرداد ۱۴۰۵  
**وضعیت**: ⏳ در حال اجرا

---

## 🔴 فاز ۱ — رفع باگ‌های بحرانی و مهم (اجرا شد)

| شناسه | فایل | توضیح | وضعیت |
|-------|------|-------|--------|
| **BUG-1** | `MediaInspector.kt` | `audioChannels` از `METADATA_KEY_NUM_TRACKS` خوانده می‌شود که تعداد **ترک**‌ها است، نه تعداد کانال صوتی! برای فایل‌های 5.1 این عدد غلط است و باعث می‌شود downmix در `convertPcmToEncoder` اشتباه باشد. باید از `METADATA_KEY_NUM_CHANNELS` استفاده شود. | ✅ رفع شد |
| **BUG-2** | `MediaCodecTranscoder.kt` | در `videoPass`، هنگام ساخت `decoderFormat` از `MediaFormat(inputFormat)` استفاده می‌شود که یک **copy constructor** نیست! این API در Android وجود ندارد — باید `inputFormat` مستقیم استفاده شود (با rotation override). | ✅ رفع شد |
| **BUG-3** | `CompressionJobService.kt` | `notifiedResults` یک `mutableSetOf<Long>()` ساده است. وقتی فرآیند restart می‌شود، این set خالی است و ممکن است notification تکراری ارسال شود. همچنین `retainAll` بر روی mutable set نیاز دارد در حالی که `jobs.keys` یک `Set<Long>` است که نوع آن match می‌کند، اما `retainAll` در Kotlin collection API باید مستقیماً روی Set به‌کار رود — ✅ این مورد از قبل درست بود. | ✅ قبلاً رفع شده |
| **BUG-4** | `HistoryEntry.kt` | `STATUS_RUNNING` تعریف شده اما در `markInterrupted` query مستقیماً string literal `'RUNNING'` استفاده شده — یعنی اگر مقدار ثابت تغییر کند query کار نمی‌کند. | ✅ رفع شد |
| **BUG-5** | `Compressor.kt` | در `compressVideo`، `summary` از `stats.durationMs / 1000` محاسبه می‌شود که برای ویدیوهای کمتر از ۱ ثانیه عدد `0s` می‌دهد. بهتر است رشته‌ای معنادار نمایش داده شود. | ✅ رفع شد |
| **BUG-6** | `SizeEstimator.kt` | `estimateAudio` فرمول کاملاً غلط دارد: `durationMs * bitrate / 8L` — bitrate در kbps است ولی فرمول بیت/ثانیه × ms / 8 است که واحد اشتباه تولید می‌کند. فرمول صحیح: `durationMs / 1000 * bitrate * 1000 / 8` یا معادل `durationMs * bitrate / 8`. **بررسی واحدها**: bitrate=128 kbps → 128,000 bps. Duration 60s → 60,000ms. `60000 * 128 / 8 = 960,000` bytes = 960 KB. صحیح! پس فرمول کنونی درست است. مشکل: `factor = 0.92` اعمال می‌شود ولی متغیر `vbr` بررسی می‌شود با `bitrateMode == VBR`. نکته: AudioSettings.bitrateMode به طور پیش‌فرض CBR است، پس vbr=false همیشه factor=1.0 — درست است. | ✅ بدون تغییر (درست بود) |
| **BUG-7** | `PhotoCompressor.kt` | در smart mode، `qualities = intArrayOf(85, 75, 65)` ولی targetBytes به ۵۰٪ سایز اصلی تنظیم می‌شود. برای عکس‌های PNG که lossless هستند، این loop کار نمی‌کند و ممکن است هیچ‌وقت به target نرسد. | ✅ رفع شد |
| **BUG-8** | `MediaCodecTranscoder.kt` → `mergePass` | در mergePass، `videoExtractor.getTrackFormat(videoIndex)` برای پیشرفت استفاده می‌شود ولی این extractor جداگانه از audio extractor است و track باید انتخاب شده باشد — این ممکن است crash کند. | ✅ بررسی و رفع شد |

---

## 🟡 فاز ۲ — سادگی + زیبایی رابط کاربری

| شناسه | فایل | توضیح | وضعیت |
|-------|------|-------|--------|
| **UI-1** | `HomeScreen.kt` | Hero Card متن «کاربر گرامی، خوش آمدید» جای ندارد — باید یک hero تمیز و مدرن باشد. | ✅ بهبود یافت |
| **UI-2** | `HomeScreen.kt` | `PremiumBanner` در بالای صفحه جا می‌گیرد و UX را کثیف می‌کند — باید به پایین انتقال یابد یا حذف شود. | ✅ بهبود یافت |
| **UI-3** | `CompressionSettingsScreen.kt` | دکمه فشرده‌سازی به صورت sticky در پایین صفحه نیست — کاربر باید scroll کند تا آن را ببیند. | ✅ رفع شد |
| **UI-4** | `ProgressScreen.kt` | `ElapsedEta` composable استفاده می‌شود ولی `ElapsedEta` import نشده — باید بررسی شود. | ✅ بررسی شد |
| **UI-5** | `HistoryScreen.kt` | وقتی filter انتخاب شده و نتیجه خالی است، همان string «سابقه‌ای یافت نشد» نشان داده می‌شود بدون اینکه filter را توضیح دهد. | ✅ بهبود یافت |
| **UI-6** | `AppSettingsScreen.kt` | بخش «درباره» باید نسخه و لینک‌های مفید داشته باشد. | ✅ بهبود یافت |

---

## 🟢 فاز ۳ — پولیش نهایی و آماده‌سازی برای انتشار

| شناسه | توضیح | وضعیت |
|-------|-------|--------|
| **POL-1** | به‌روزرسانی ROADMAP با وضعیت نهایی | ✅ |
| **POL-2** | نوشتن ROUND14_REPORT.md با خلاصه تمام تعمیرات | ✅ |
| **POL-3** | Commit و Push | ✅ |
