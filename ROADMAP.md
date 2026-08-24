# 🔧 نقشه راه تعمیرات و بهینه‌سازی Compressly

## تاریخ: 2026-08-24

---

## ✅ وضعیت اجرا: تمام فازها تکمیل شد

---

## 🔍 بررسی ریزبینانه - نقص‌های یافت‌شده و تعمیرشده

### 🔴 فاز ۱: تعمیر بحرانی — ✅ تکمیل

| # | فایل | نقص | تعمیر |
|---|------|------|-------|
| C2 | `MediaCodecTranscoder.kt` | `var lastPts` ایندنت نادرست | ✅ ایندنت اصلاح شد |
| C3 | `MediaCodecTranscoder.kt` | `if(pts < audioOffset)` بدون فاصله | ✅ فاصله و ایندنت اصلاح شد |
| C4 | `MediaCodecTranscoder.kt` | `if (writeVideo)` ایندنت نادرست | ✅ ایندنت اصلاح شد |
| F4 | `MediaCodecTranscoder.kt` | `lastPts = -1L` مقدار اولیه نامناسب | ✅ به `0L` تغییر یافت |

### 🟡 فاز ۲: تعمیر عملکردی — ✅ تکمیل

| # | فایل | نقص | تعمیر |
|---|------|------|-------|
| F1-F2 | `AacTranscoder.kt` | فراخوان مضاعف stop/release در try و finally | ✅ حذف فراخوان‌های صریح از بلوک try؛ فقط finally مسئول پاکسازی است |
| F3 | `OutputStore.kt` | SimpleDateFormat نه thread-safe | ✅ کش ConcurrentHashMap + synchronized برای ایمنی ترد |
| F5 | `SoundEffects.kt` | نشت ترد صدا با Thread.sleep بلند | ✅ حلقه نظرسنجی ۲۰ms با بررسی playState برای آزادسازی بهنگام |
| F6 | `SettingsViewModel.kt` | رقابت در خواندن/نوشتن selection | ✅ خواندن و null کردن atomic در init |

### 🟠 فاز ۳: زیبایی‌سازی و ساده‌سازی — ✅ تکمیل

| # | فایل | نقص | تعمیر |
|---|------|------|-------|
| U1 | `Type.kt` | `titleSmall` وزن نامناسب | ✅ fontWeight به Medium و letterSpacing به 0.15sp تغییر یافت |
| U5 | `PresetGauge.kt` | رادیو با RoundedCornerShape(11.dp) | ✅ به CircleShape یکدست تغییر یافت |
| U7 | `Theme.kt` / `Color.kt` | فاقد inversePrimary/inverseSurface | ✅ رنگ‌های معکوس برای هر دو تم Light و Dark اضافه شد |

### 🔵 فاز ۴: کیفیت کد — ✅ تکمیل

| # | فایل | نقص | تعمیر |
|---|------|------|-------|
| Q1-Q3 | فایل‌های مختلف | تکرار convertPcmToEncoder/putLimited/findTrack | ✅ فایل مشترک `MediaUtil.kt` ایجاد شد و هر سه فایل به آن ارجاع دادند |
| Q5 | `MainActivity.kt` | ایمپورت استفاده‌نشده kotlinx.coroutines.launch | ✅ حذف شد |
| Q6 | `MediaCodecTranscoder.kt` | ایندنت ناهموار | ✅ تمام بلوک‌های ناهموار اصلاح شدند |

---

## 📁 فایل‌های تغییر یافته

1. `core/engine/audio/AacTranscoder.kt` — حذف double-release، استفاده از MediaUtil
2. `core/engine/video/MediaCodecTranscoder.kt` — اصلاح ایندنت، lastPts، استفاده از MediaUtil
3. `core/engine/MediaUtil.kt` — **جدید** — توابع مشترک
4. `core/util/SoundEffects.kt` — اصلاح نشت ترد صدا
5. `core/data/OutputStore.kt` — thread-safe date formatting
6. `ui/viewmodels/SettingsViewModel.kt` — محافظت رقابت init
7. `ui/theme/Type.kt` — اصلاح titleSmall
8. `ui/theme/Theme.kt` — افزودن inverse colors
9. `ui/theme/Color.kt` — افزودن inverse color values
10. `ui/components/PresetGauge.kt` — CircleShape یکدست
11. `MainActivity.kt` — حذف ایمپورت استفاده‌نشده
12. `ui/screens/HomeScreen.kt` — حفظ spin (برای RotatingGear)

---

## 🏁 محصول نهایی

- ✅ بدون نقص بحرانی (کرش/بلد فیلچر)
- ✅ بدون نقص عملکردی شناخته‌شده
- ✅ کد DRY (توابع مشترک استخراج شدند)
- ✅ UI یکدست و حرفه‌ای (تم کامل، CircleShape، typography درست)
- ✅ ایمنی ترد (SimpleDateFormat، SoundEffects، Selection race)
- ✅ ایندنت و فرمت تمیز
