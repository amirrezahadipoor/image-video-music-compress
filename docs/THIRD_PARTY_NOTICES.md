# Third-Party Notices — حجمینو / Compressly

این فایل تعهدات مجوز کتابخانه‌های شخص ثالث را برای کاربران و بازبین‌ها روشن
می‌کند. متن کامل هر مجوز در مخزن خود کتابخانه است؛ لینک‌ها پایین‌اند.

## خلاصه

| کتابخانه | نسخه | مجوز | نحوهٔ استفاده |
|---|---|---|---|
| AndroidX (Core, AppCompat, Activity, Lifecycle, Navigation, Splashscreen, ExifInterface, DocumentFile, ProfileInstaller) | various | Apache-2.0 | وابستگی معمولی |
| Jetpack Compose + Material 3 (BOM) | 2026.04.01 | Apache-2.0 | UI |
| Jetpack Room (runtime, ktx, compiler) | — | Apache-2.0 | پایگاه دادهٔ تاریخچه |
| DataStore Preferences | — | Apache-2.0 | تنظیمات |
| Kotlin stdlib + kotlinx-coroutines | — | Apache-2.0 | همروندی |
| Coil (compose, video) | — | Apache-2.0 | بارگذاری تصویر/ویدیو بندانگشتی |
| AndroidX Media3 (ExoPlayer, UI, Common) | 1.5.1 | Apache-2.0 | پخش پیش‌نمایش ویدیو/صدا |
| **jump3r** (`de.sciss:jump3r`) | 1.0.5 | **LGPL 2.1** (طبق POM) | انکودر MP3 (پورت خالص جاواِ LAME) |
| **jaudiotagger** (`net.jthink:jaudiotagger`) | 3.0.1 | **LGPL** — تأییدشده از POM منتشرشدهٔ Maven Central (`<name>LGPL</name>`، `gnu.org/copyleft/lesser.html`)، متن مجوز در `docs/licenses/lgpl-2.1.txt` | خواندن/نوشتن تگ MP3/M4A/FLAC |
| Adivery SDK (`com.adivery:sdk`) | 4.9.0 | مالکانه (شرایط خود خدمت) | فقط flavor `bazaar` — تبلیغات |
| Poolakey (`com.github.cafebazaar.Poolakey`) | 2.2.0 | **Apache-2.0** — تأییدشده (فیلد `license` در API گیت‌هاب: `Apache-2.0`؛ برخلاف ادعای قبلی این سند، GPL نیست) | فقط flavor `bazaar` — پرداخت کافه‌بازار |

## تعهدات LGPL (jump3r و jaudiotagger)

کتابخانه‌های LGPL اجازهٔ استفاده در یک برنامهٔ غیرمتحرک را می‌دهند، به شرط اینکه:

1. **اطلاع‌رسانی** شود که از آن‌ها استفاده شده و متن مجوز در دسترس باشد → این فایل.
2. **منبع کتابخانه** در دسترس باشد → هر دو متن‌بازند (لینک‌ها پایین).
3. کاربر بتواند نسخهٔ **تعویض‌شدنی/اصلاح‌شده**ٔ کتابخانه را بسازد — که برای برنامه‌ای
   که کد منبعش عمومی است (این مخزن) خودبه‌خود برآورده است: ماژول `app` در همین
   ریپو هست و می‌توان با ویرایش `app/build.gradle.kts` نسخهٔ دیگری را لینک کرد و
   بیلد گرفت.

**اقدام باز (در `docs/KNOWN_ISSUES.md` شمارهٔ ۸):** این متن در `NOTICE` و
`docs/THIRD_PARTY_NOTICES.md` هست، ولی هنوز در خود اپ «صفحهٔ مجوزها» ندارد.
افزودن یک ورودی کوچک به صفحهٔ حریم خصوصی (همین فایل را نشان دهد) تنها کار
باقی‌مانده است.

## تأیید مجوزها (نتیجهٔ استعلام)

این بخش جای «استعلام باز» قبلی را گرفته است. هر ردیف با یک منبع قابل‌بررسی
تأیید شده، نه با حدس:

| کتابخانه | ادعای قبلی | نتیجهٔ بررسی | منبع بررسی |
|---|---|---|---|
| Poolakey 2.2.0 | «GPL-3.0» | **Apache-2.0** | فیلد `license` در `api.github.com/repos/cafebazaar/Poolakey` → `Apache-2.0` |
| jaudiotagger 3.0.1 | «LGPL یا GPL» | **LGPL** | POM رسمی `repo1.maven.org/maven2/net/jthink/jaudiotagger/3.0.1/*.pom` → `<name>LGPL</name>` |
| jump3r 1.0.5 | LGPL 2.1 | **LGPL 2.1** (بدون تغییر) | POM + `git.iem.at/sciss/jump3r` |

**نتیجهٔ حقوقی:** هیچ وابستگی GPL در بیلد نیست. Poolakey (تنها وابستگی‌ای که
برچسب GPL خورده بود) Apache-2.0 است، پس لینک کردنش در یک بستهٔ بسته هیچ تعهدی
برای انتشار سورس ایجاد نمی‌کند. دو وابستگی LGPL (jump3r برای انکود MP3 و
jaudiotagger برای تگ) با رعایت سه شرط بخش بالا استفاده می‌شوند و متن کامل
مجوزشان در [`docs/licenses/lgpl-2.1.txt`](licenses/lgpl-2.1.txt) و
[`docs/licenses/apache-2.0.txt`](licenses/apache-2.0.txt) در همین مخزن است —
یعنی شرط «متن مجوز همراه برنامه» مستقل از اینترنت هم برآورده می‌شود.

**تنها مورد باز:** شرط‌های استفاده از Adivery (SDK مالکانه) و شناسه‌های
برنامه؛ `docs/KNOWN_ISSUES.md` شمارهٔ ۷.

## لینک‌ها

- jump3r (صفحهٔ رسمی پروژه، میزبان iem.at نه GitHub): <https://git.iem.at/sciss/jump3r>
  و Maven Central: <https://mvnrepository.com/artifact/de.sciss/jump3r>
  (LAME اصلی: <https://lame.sourceforge.io>)
- jaudiotagger: <https://bitbucket.org/ijabz/jaudiotagger> و
  <https://mvnrepository.com/artifact/net.jthink/jaudiotagger/3.0.1>
- AndroidX / Compose: <https://developer.android.com/jetpack/androidx>
- Media3: <https://github.com/androidx/media>
- Coil: <https://coil-kt.github.io/coil/>
- Poolakey: <https://github.com/cafebazaar/Poolakey> (مجوز: Apache-2.0)
- متن مجوزهای همراه: `docs/licenses/lgpl-2.1.txt`، `docs/licenses/apache-2.0.txt`
- Adivery: <https://adivery.com>
