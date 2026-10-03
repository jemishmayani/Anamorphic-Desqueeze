<p align="center">
  <img src="docs/icon.png" width="128" alt="Anamorphic De-Squeeze icon">
</p>

<h1 align="center">Anamorphic De-Squeeze</h1>

<p align="center">
  A small, offline Android app that de-squeezes anamorphic video, built for
  <b>DJI Osmo Pocket</b> D-Log 10-bit HEVC footage shot with an anamorphic lens or adapter.
</p>

<p align="center">
  <a href="https://github.com/jemishmayani/Anamorphic-Desqueeze/releases/latest"><b>⬇ Download the latest APK</b></a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

## What it does

An anamorphic lens squeezes a wide scene horizontally onto the sensor, so the raw clip looks
thin and stretched. This app restores the correct shape by widening the image by the lens's
**squeeze factor**:

> 1920 × 1080 at **1.33×** → displays as **2554 × 1080**
> 3840 × 2160 at **1.33×** → displays as **5107 × 2160**

"Squeeze factor" here always means the lens squeeze, never a target aspect ratio. The app
**never crops** to 16:9, 2.39:1 or anything else. The whole frame is kept.

**Workflow:** Choose video → pick squeeze factor → preview → (optional LUT) → export.

The original file is never modified. Exports are saved to `Movies/AnamorphicDesqueeze/`
as `ORIGINALNAME_DESQUEEZED_1.33X.mp4` (or `.mov` for MOV sources in Lossless mode).

## Two export methods

| | **Lossless** (default) | **Re-encode** |
|---|---|---|
| How it works | Copies the file byte-for-byte and adds a pixel-aspect-ratio tag (`pasp`), like setting pixel aspect in DaVinci Resolve | Decodes, stretches the pixels on the GPU, and encodes a new video |
| Quality | Bit-identical to the original: 10-bit D-Log, bitrate, frame rate, audio and metadata untouched | Re-compressed; quality depends on the chosen preset |
| Speed | About as fast as copying the file | Real-time-ish; depends on the phone |
| Resolution | Always full original resolution, any squeeze | Limited by the phone's video encoder (often 4096 px wide), so large squeezes on 4K are scaled down evenly |
| LUTs | Not applied | Can apply a `.cube` LUT |
| Compatibility | Shown de-squeezed by apps that respect pixel aspect (VLC, DaVinci Resolve, Premiere Pro, Final Cut Pro, most desktop players). Some phone galleries, social apps and web players ignore the tag and show it squeezed | Shown de-squeezed everywhere, because the pixels themselves are wider |

**Tip:** use **Lossless** when the clip is going into an editor; use **Re-encode** when you
want a finished file to share directly or to burn in a LUT. In DaVinci Resolve, if a
Lossless clip still looks squeezed, check *Clip Attributes → Pixel Aspect Ratio*.

### Why Re-encode output can be smaller than "4K"

Phone hardware encoders have a maximum frame width; see **Settings → Export limits** for
yours (for example, 4096 px). De-squeezing 4K footage at 1.33× needs a 5107 px wide frame,
so Re-encode shrinks the whole frame evenly to fit (about 4096 × 1732), keeping the shape
correct and never cropping. Editors such as LumaFusion instead letterbox or crop into a
3840 × 2160 frame; at 1.33× the picture inside that frame is about 3840 × 1624, so the
actual image detail is similar or lower.

## Features

- **Squeeze presets:** 1.2×, 1.33×, 1.5×, 1.55×, 1.6×, 1.8×, 2.0×, plus a custom factor (slider or typed, 1.0–3.0×)
- **Default squeeze factor** saved in Settings and used for new imports (unless you've already picked one)
- **Live preview** with a Squeezed / De-squeezed toggle, using hardware playback (the original file is never touched)
- **Clip info:** resolution, codec, bit depth, frame rate and color tags (e.g. `3840×2160, HEVC 10-bit, 30 fps, BT.709 SDR limited`)
- **Batch export:** select several clips and export them all with the same settings
- **LUT library** (Re-encode): import, select, rename and delete your own 3D `.cube` LUTs, with 0–100% strength. No LUTs are bundled; for D-Log → Rec.709, import DJI's official LUT from DJI's website
- **Re-encode options:** HEVC (preferred) or H.264; Maximum / High / Balanced / Smaller File quality; frame rate and audio kept; automatic retries with safer settings if the encoder refuses
- **Export limits screen:** shows what your phone's hardware can do: 10-bit HEVC decode, maximum frame size, maximum squeeze for 4K and 1080p, maximum frame rate and bitrate, and the full codec list
- **Crash reports:** if the app ever closes unexpectedly, it shows a report on next launch that you can copy and send
- Light, dark or system theme; works fully offline; no ads, accounts or tracking, and no internet permission

## Install

1. Open the [latest release](https://github.com/jemishmayani/Anamorphic-Desqueeze/releases/latest) on your phone.
2. Download the `.apk` file under **Assets** and open it.
3. Allow **Install unknown apps** for your browser or file manager when Android asks.
   Google Play Protect may warn about an unknown developer; tap **Install anyway**.

**Requirements:** Android 10 or newer. 10-bit HEVC playback and Re-encode need hardware
support, which most phones from 2019 onwards have.

**Updating:** each build is currently signed with a temporary key, so you may need to
uninstall the previous version before installing a new one.

## Known limitations

- **Re-encode is 8-bit for D-Log footage.** DJI D-Log clips are tagged as standard (SDR)
  video, and Android's GPU video pipeline processes SDR at 8-bit, so Re-encode output is
  8-bit. Use Lossless to keep true 10-bit. Clips tagged HLG/PQ HDR stay 10-bit in Re-encode.
- **LUTs are applied at 8-bit precision**, and only in Re-encode. The preview shows framing only, not the LUT.
- **Some DJI-specific metadata** may not carry over in Re-encode (Lossless keeps everything).
- **Keep the app open while exporting**; the screen stays on automatically.

## How it works (technical)

| Stage | Technology |
|---|---|
| Lossless | Pure Kotlin MP4/MOV box editor: rewrites only the `moov` header, inserting or updating a `pasp` box in the video sample entry (`hvc1`/`hev1`/`avc1`/`avc3`) and shifting `stco`/`co64` chunk offsets when the header precedes the media data. Media is streamed in 4 MB chunks, so RAM use stays flat for multi-GB files |
| Decode / encode | Android MediaCodec (hardware) via [AndroidX Media3 Transformer](https://developer.android.com/media/media3/transformer) |
| De-squeeze (Re-encode) | OpenGL ES `Presentation` effect, stretch-to-fit to the target size |
| LUT | Media3 `SingleColorLut` (3D LUT on the GPU); strength is blended into the LUT table itself |
| Audio | Passed through unchanged (no re-encode) |
| Preview | ExoPlayer with on-screen stretch |
| UI | Jetpack Compose, Material 3 |

No FFmpeg is bundled, which keeps the app small (about 24 MB) and avoids GPL/LGPL licensing issues.

Source layout (`app/src/main/java/com/desqueeze/app/`):

| File | Purpose |
|---|---|
| `MainActivity.kt` | Main screen, settings screen, shared UI components |
| `AppState.kt` | Screen state that survives navigation |
| `PaspWriter.kt` | Lossless pixel-aspect tagging |
| `Exporter.kt` | Re-encode pipeline, encoder-size fitting, retries, saving to the gallery |
| `VideoProbe.kt` | Reads resolution, codec, bit depth, frame rate, color info |
| `DeviceCaps.kt`, `LimitsScreen.kt` | Hardware codec capabilities and the Export limits screen |
| `LutManager.kt` | `.cube` parsing and the LUT library |
| `Settings.kt` | Saved preferences |
| `Diag.kt` | Crash, freeze and low-memory reports |
| `Theme.kt` | Colors and typography |

## Building it yourself

Every push to `main` builds the APK automatically with GitHub Actions
(`.github/workflows/build.yml`). The APK appears under the run's **Artifacts** and on the `apk` branch.

To publish a release, add a section for the new version to [`CHANGELOG.md`](CHANGELOG.md),
bump `versionCode`/`versionName` in `app/build.gradle.kts`, then push a tag:

```sh
git tag v1.5
git push origin v1.5
```

The workflow builds the APK, creates the GitHub Release, and uses that version's
CHANGELOG section as the release notes.

To build locally, open the project in Android Studio (JDK 17, Android SDK 34) and choose
**Build → Build APK(s)**, or run `gradle :app:assembleRelease`.
