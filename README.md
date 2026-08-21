# Compressly

**Photos. Videos. Music. Smaller.**

Compressly is a professional, fully-offline media compressor for Android — photos, videos and audio/music files in one app. Everything runs on your device. No internet, no accounts, no uploads, ever.

![Compressly banner](art/banner.png)

---

## Highlights

- **100% offline.** All compression runs on-device with native Android APIs (MediaCodec, MediaExtractor, MediaMuxer, BitmapFactory, ExifInterface). The app has no INTERNET permission and works in airplane mode from first launch.
- **One coherent grading system.** Photos, videos and audio share the same 4-tier gauge — *Maximum Quality*, *Balanced*, *High Compression*, *Maximum Compression* — with honest estimated size-reduction ranges shown before you commit, plus an Advanced/Custom mode for fine control.
- **Quality-first engines.** Hardware-accelerated video transcoding via a MediaCodec surface pipeline (H.264/H.265, software fallback), sampled photo decoding with EXIF handling, and hardware AAC encoding with an embedded pure-Java LAME MP3 encoder.
- **Batch everything.** Compress many files at once with one profile; per-file and overall progress; cancel individual items or the whole job.
- **Runs in the background.** Long jobs live in a foreground service with a progress notification and Pause/Resume/Cancel actions.
- **Real numbers.** Original size, estimated output, actual output and % reduction — before, during and after.
- **No ads, no sign-up.** Just a clean, offline, premium app.

## Features

### Photos
- Input: JPEG, PNG, WebP, HEIC/HEIF (read; Android 9+ for HEIC)
- Output: JPEG, WebP, PNG, or keep the source format
- Quality slider + resize presets (2560 / 1920 / 1280 / 1024 / custom, aspect locked)
- EXIF metadata preserved by default, or stripped for privacy
- Live draggable before/after preview with real-time size estimates
- Batch compression with per-photo progress

### Videos
- Input: MP4, MOV, MKV, WebM
- Output: MP4 (H.264/AVC or H.265/HEVC where hardware-supported; trade-offs explained in-app)
- Resolution (original / 1080p / 720p / 480p / custom), bitrate, frame rate
- Audio track: keep, compress separately, or remove
- Trim (in/out points) before compressing
- Foreground service with persistent notification; pause and cancel supported
- Estimated size before starting; actual size + ratio when done

### Audio / Music
- Input: MP3, WAV, FLAC, AAC, OGG, M4A
- Output: AAC (M4A) via hardware encoder, or MP3 via the embedded pure-Java LAME port — CBR or VBR, 64–320 kbps
- ID3/MP4 tags (title, artist, album, cover art) preserved by default, with a strip option
- Batch support and a lightweight waveform preview

### App
- History of every compression with before/after sizes and quick share/open
- Share sheet and "save to folder" (MediaStore: Pictures/Movies/Music/Compressly)
- System file pickers only (Photo Picker on Android 13+, Storage Access Framework elsewhere)
- Light/dark themes following the system, with manual override
- English + Persian (فارسی) UI
- Fully offline — no network permission, no analytics, no ads

## Screens (flow)

Home (modules + space saved) -> system file picker -> Compression Settings (4-tier gauge, live estimates, before/after preview for photos, advanced panel) -> animated Progress (foreground service, pause/cancel per item or per job) -> Result (before/after sizes, reduction, share/open) -> History (per-file savings) and App Settings (theme, defaults, about). Real screenshots will be added after the first device build.

## Tech stack

- **Language:** Kotlin 2.1, Jetpack Compose (Material 3)
- **Architecture:** MVVM — Compose UI, ViewModels, repository + engine layer
- **Concurrency:** Coroutines + Flow, all heavy work on background dispatchers
- **Video:** MediaCodec / MediaExtractor / MediaMuxer surface pipeline
- **Photo:** BitmapFactory / ExifInterface (framework)
- **Audio:** MediaCodec AAC + `de.sciss:jump3r` (pure-Java LAME, used at the low level to stay Android-compatible) + `net.jthink:jaudiotagger` for metadata
- **Persistence:** Room (history), DataStore (settings)
- **minSdk 26 / targetSdk 35 / compileSdk 35**

## Build

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (R8; add your signing config first)
./gradlew lint                 # static analysis
```

Open the project in Android Studio (Ladybug or newer), sync, and run on a device or emulator. No network permissions are requested or needed.

## Architecture

```
ui/        Compose screens, ViewModels, theme, reusable components
core/
  engine/  photo | video | audio engines, size estimator, media inspector
  service/ JobCoordinator + foreground service + notifications
  data/    Room history, DataStore settings, MediaStore output publishing
  util/    formatting, storage, mime helpers
```

A job goes: pick files -> settings screen (preset gauge + estimates) -> `JobCoordinator.enqueue` -> foreground service -> per-file engine -> MediaStore publish -> history + result.

## Honest limitations

- Outputs are always saved to the standard media folders (`Pictures/Movies/Music/Compressly`) via MediaStore. A custom "default output folder" setting is intentionally not offered — Android's scoped storage makes fixed media folders the reliable, gallery-visible choice.
- **HEIC/HEIF photos** require Android 9+ (API 28). Below that the app explains the limitation instead of failing silently.
- **MP3 encoding** uses a pure-Java port of LAME (jump3r), which is fully offline but slower than a hardware encoder; AAC/M4A output is hardware-accelerated and generally the better choice.
- **H.265 output** depends on the device having an HEVC encoder; otherwise the app falls back to H.264 with a clear note.
- On Android 14+, `dataSync` foreground services are time-limited (6 hours) — effectively a non-issue for typical files.
- Metadata writing is best-effort: the encoded file is always delivered even if tagging fails.

## License

Add a `LICENSE` file of your choice before publishing. This project's third-party pieces: jump3r (GPL/LGPL lineage — verify for your distribution), jaudiotagger (LGPL).
