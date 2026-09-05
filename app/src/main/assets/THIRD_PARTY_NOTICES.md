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
| **jaudiotagger** (`net.jthink:jaudiotagger`) | 3.0.1 | **LGPL** (POM به «GNU Lesser General Public License» ارجاع می‌دهد؛ بعضی فهرست‌ها GPL-3.0 را هم ذکر کرده‌اند → قبل از انتشار از POM رسمی تأیید شود) | خواندن/نوشتن تگ MP3/M4A/FLAC |
| Adivery SDK (`com.adivery:sdk`) | 4.9.0 | مالکانه (شرایط خود خدمت) | فقط flavor `bazaar` — تبلیغات |
| Poolakey (`com.github.cafebazaar.Poolakey`) | 2.2.0 | **باید تأیید شود** (به‌طور رسمی GPL-3.0 ذکر شده) | فقط flavor `bazaar` — پرداخت کافه‌بازار |

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

## استعلام باز

- **Poolakey**: مجوز اعلامی در مخزن GitHub آن GPL-3.0 است. اگر درست باشد، لینک
  کردنش در بیلد بستهٔ «پخش‌شده در کافه‌بازار» نیاز به انتشار منبع ماژول billing
  (یا دریافت مجوز تجاری از کافه‌بازار) دارد. از آنجا که کل مخزن `app` عمومی است،
  تعهد عمل می‌کند — ولی **قبل از انتشار بعدی، مجوز اعلامی را از مخزن رسمی
  Poolakey تأیید کنید** و در همین فایل بنویسید.
- **Adivery**: شرط‌های استفاده از SDK و شناسه‌های برنامۀ committed را ببینید
  (`docs/KNOWN_ISSUES.md` شمارهٔ ۷).

## لینک‌ها

- jump3r (صفحهٔ رسمی پروژه، میزبان iem.at نه GitHub): <https://git.iem.at/sciss/jump3r>
  و Maven Central: <https://mvnrepository.com/artifact/de.sciss/jump3r>
  (LAME اصلی: <https://lame.sourceforge.io>)
- jaudiotagger: <https://bitbucket.org/ijabz/jaudiotagger> و
  <https://mvnrepository.com/artifact/net.jthink/jaudiotagger/3.0.1>
- AndroidX / Compose: <https://developer.android.com/jetpack/androidx>
- Media3: <https://github.com/androidx/media>
- Coil: <https://coil-kt.github.io/coil/>
- Poolakey: <https://github.com/cafebazaar/Poolakey>
- Adivery: <https://adivery.com>
