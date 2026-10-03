<p align="center">
  <img src="docs/icon.png" width="128" alt="Anamorphic De-Squeeze icon">
</p>

<h1 align="center">Anamorphic De-Squeeze</h1>

<p align="center">
  A small, offline Android app that de-squeezes anamorphic video from <b>any camera</b>:
  phones, action cams, gimbals, mirrorless and cinema cameras shooting through an anamorphic lens or adapter.
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

**Workflow:** four simple steps.

| Step | What you do |
|---|---|
| **1. Clips** | Pick one or more videos. Each shows a thumbnail, duration, resolution, bit depth and detected log/HDR |
| **2. Frame** | Choose the squeeze factor, orientation and desqueeze direction, and watch the live preview |
| **3. Look** | Optionally add a LUT: preview it live, set strength, compare before/after |
| **4. Export** | Follow the recommendation (or pick the alternative), check the size and time estimate, export |

The original file is never modified. Exports are saved to `Movies/AnamorphicDesqueeze/`
as `ORIGINALNAME_DESQUEEZED_1.33X.mp4` (or `.mov` for MOV sources in Lossless mode).

## Works with your camera

The app isn't tied to one device. It reads each clip's header and shows what it finds as
badges, with full details one tap away:

| Detected | How |
|---|---|
| **Log profile:** D-Log / D-Log M, S-Log2/3, V-Log, Canon Log 2/3, F-Log/F-Log2, N-Log, Apple Log, L-Log, I-Log, Samsung Log, GoPro Log, Z-Log2, Blackmagic Film, ARRI LogC, RED Log3G10 | From the file's metadata when the camera records it. If it doesn't, a frame is checked for the flat look of log (lifted blacks, compressed range, low saturation) and shown as **"Looks like log"**, clearly marked as an estimate |
| **HDR:** HLG, HDR10 (PQ), Dolby Vision; mastering metadata | Color tags and Dolby Vision boxes in the video track |
| **SDR** | Standard transfer tags and no log detected |
| **Bit depth & chroma:** 8/10/12-bit, 4:2:0, 4:2:2, 4:4:4 | Read from the codec configuration (`hvcC`, `avcC`, `av1C`, `vpcC`, ProRes type) |
| **Codec & profile:** HEVC, H.264, AV1, VP9, ProRes (Proxy to 4444 XQ), MPEG-4 | Sample entry type |
| **Color:** primaries (BT.709, BT.2020, P3), transfer, matrix, full/limited range | `colr` box, falling back to Android's track info |
| **Camera make & model** | QuickTime/iTunes metadata, Sony XML, or brand names in the header |
| **Existing pixel-aspect tag** | `pasp` box (shown as "Tagged 1.33×") |
| **Frame rate, audio format, duration, size, average bitrate** | Android's media extractor |

Only the header is read, so analysis is instant even for multi-GB files.

## Vertical anamorphic

When a phone or camera is turned 90° with the anamorphic lens attached, the squeeze ends up
on the picture's vertical axis. The **Frame** step has two controls for this:

| Control | Options |
|---|---|
| **Orientation** | **Auto** uses the file's rotation metadata; **Horizontal** / **Vertical** force a landscape or portrait display, for files with missing or wrong rotation |
| **Desqueeze direction** | **Auto** stretches along the lens's squeeze axis (the sensor's horizontal axis, so vertically on screen when the clip is rotated 90°); **Horizontal** / **Vertical** choose the on-screen axis yourself |

Lossless writes a vertical pixel aspect (for example `100:133`) and, if you override orientation,
a corrected rotation matrix, still without touching the video data. Re-encode rotates and
stretches on the GPU.

## Two export methods

| | **Lossless** (default) | **Re-encode** |
|---|---|---|
| How it works | Copies the file byte-for-byte and adds a pixel-aspect-ratio tag (`pasp`), like setting pixel aspect in DaVinci Resolve | Decodes, stretches the pixels on the GPU, and encodes a new video |
| Quality | Bit-identical to the original: bit depth, log/HDR, bitrate, frame rate, audio and metadata untouched | Re-compressed; quality depends on the chosen preset |
| Speed | About as fast as copying the file | Real-time-ish; depends on the phone |
| Resolution | Always full original resolution, any squeeze | Limited by the phone's video encoder (often 4096 px wide), so large squeezes on 4K are scaled down evenly |
| LUTs | Not applied | Can apply a `.cube` LUT |
| Compatibility | Shown de-squeezed by apps that respect pixel aspect (VLC, DaVinci Resolve, Premiere Pro, Final Cut Pro, most desktop players). Some phone galleries, social apps and web players ignore the tag and show it squeezed | Shown de-squeezed everywhere, because the pixels themselves are wider |

**Tip:** use **Lossless** when the clip is going into an editor; use **Re-encode** when you
want a finished file to share directly or to burn in a LUT. In DaVinci Resolve, if a
Lossless clip still looks squeezed, check *Clip Attributes → Pixel Aspect Ratio*.

### Why Re-encode output can be smaller than "4K"

Phone hardware encoders have a maximum frame width; see **Settings → Device diagnostics** for
yours (for example, 4096 px). De-squeezing 4K footage at 1.33× needs a 5107 px wide frame,
so Re-encode shrinks the whole frame evenly to fit (about 4096 × 1732), keeping the shape
correct and never cropping. Editors such as LumaFusion instead letterbox or crop into a
3840 × 2160 frame; at 1.33× the picture inside that frame is about 3840 × 1624, so the
actual image detail is similar or lower.

## Features

- **Squeeze presets:** 1.2×, 1.33×, 1.5×, 1.55×, 1.6×, 1.8×, 2.0×, plus a custom factor (slider or typed, 1.0–3.0×)
- **Default squeeze factor** in Settings: pick a preset or set any custom value. It's used for new imports unless you've already picked a factor
- **Cinema-style preview:** the frame springs between squeezed and de-squeezed shapes; press and hold to compare with the original; play/pause, mute, a live aspect-ratio readout; a **filmstrip timeline** of thumbnails with a playhead to tap or drag. Hardware playback; the file is never touched
- **LUT preview:** LUT off / on while the video plays (rendered at a lighter ~720p proxy so 4K 10-bit stays smooth), live strength, and **Compare**: a full-quality still with a draggable before/after divider
- **Smart recommendations:** a plain-language suggestion for each clip (for example "Lossless Desqueeze: preserves your original 10-bit D-Log footage") with the alternative and why
- **Estimates before export:** output size and processing time, labelled as estimates; they learn this phone's real speed from your exports
- **Clip list with thumbnails:** duration, resolution, bit depth and log/HDR at a glance; add or remove clips
- **Footage badges:** log, HDR/SDR, bit depth, chroma, codec, frame rate, gamut, existing pixel-aspect tag and audio, each with its own icon; tap **All details** for the full technical breakdown
- **Batch export:** select several clips and export them all with the same settings
- **LUT library** (Re-encode): import, select, rename and delete your own 3D `.cube` LUTs, with 0–100% strength. No LUTs are bundled; import your camera maker's official log-to-Rec.709 LUT
- **Re-encode options:** HEVC (preferred) or H.264; Maximum / High / Balanced / Smaller File quality; frame rate and audio kept; automatic retries with safer settings if the encoder refuses
- **Per-clip recommendations:** switch clips on the Frame, Look and Export steps; each clip gets its own recommendation and export method (or apply one to all)
- **Device diagnostics:** a "what this means for you" summary; your phone's chipset, memory, storage, display HDR and graphics; measured and manufacturer speeds; a ✓ / ⚠ / ✗ checklist of HEVC, 10-bit HEVC, H.264 and AV1 decode; HEVC, 10-bit and H.264 encode; maximum frame width, 4K frame rate and bitrate. Each item explains what it means for you, and a squeeze check shows which squeezes Re-encode can make at full size on 4K and 1080p
- **Crash reports:** if the app ever closes unexpectedly, it shows a report on next launch that you can copy and send
- **Organized settings** with defaults, Re-encode options, output, LUT library, theme, diagnostics, and **About & support** (check for updates, what's new, source code, privacy, licenses, and [support the development](https://buymeacoffee.com/jemishmayani))
- Light, dark or system theme; works fully offline; no ads, accounts or tracking. The GitHub version goes online only when you tap *Check for updates*; the Play version never does

## Install

1. Open the [latest release](https://github.com/jemishmayani/Anamorphic-Desqueeze/releases/latest) on your phone.
2. Download the `.apk` file under **Assets** and open it.
3. Allow **Install unknown apps** for your browser or file manager when Android asks.
   Google Play Protect may warn about an unknown developer; tap **Install anyway**.

**Requirements:** Android 10 or newer (built for Android 16 / API 36). 10-bit HEVC playback and Re-encode need hardware
support, which most phones from 2019 onwards have.

**Updating:** from v1.7, every release is signed with the same permanent key, so new versions
install straight over the old one. (If you have v1.6 or older, uninstall it once first: those
builds used temporary keys.)

## Known limitations

- **Re-encode is 8-bit for log footage.** Log clips are almost always tagged as standard (SDR)
  video, and Android's GPU video pipeline processes SDR at 8-bit, so Re-encode output is
  8-bit. Use Lossless to keep true 10-bit. Clips tagged HLG/PQ HDR stay 10-bit in Re-encode.
- **Log detection depends on the camera.** Many cameras write the profile name into the file;
  some phones and action cams don't, and then the app can only estimate from the picture.
- **Preview and Re-encode need the phone to decode the codec.** ProRes, for example, usually
  can't be played on Android; Lossless tagging still works for it.
- **LUTs are applied at 8-bit precision**, and only in Re-encode. The live LUT preview uses a ~720p proxy; Compare shows a full-quality still. On some phones live effects aren't supported; the app then says so and Compare still works.
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
| `VideoProbe.kt` | Combines header analysis with Android's track info |
| `FootageAnalyzer.kt` | Camera-agnostic header analysis: log, HDR, bit depth, chroma, color, camera |
| `FootageCard.kt`, `AppIcons.kt` | Footage badges, details and the custom icon set |
| `Steps.kt` | The four-step flow: Clips, Frame, Look, Export |
| `Geometry.kt` | Orientation + desqueeze direction resolved into rotation, output size and pixel aspect |
| `PreviewPlayer.kt` | Preview: hold-to-compare, live LUT, before/after still, filmstrip timeline |
| `Frames.kt` | Thumbnails and still frames in display orientation |
| `Recommend.kt` | Plain-language export recommendations |
| `Estimates.kt` | Size/time estimates and learned device speed |
| `DeviceCaps.kt`, `LimitsScreen.kt` | Hardware codec capabilities and the Device diagnostics screen |
| `LutManager.kt` | `.cube` parsing and the LUT library |
| `Settings.kt` | Saved preferences |
| `SettingsScreen.kt` | Settings, LUT library, About & support, update check |
| `Diag.kt` | Crash, freeze and low-memory reports |
| `Theme.kt` | Colors and typography |

## Google Play

The app meets Google Play's current requirements: it targets Android 16 (API 36), its native
library supports 16 KB memory pages, it has no unnecessary permissions, and it ships as an
Android App Bundle. It's built in two versions:

| Version | File | Differences |
|---|---|---|
| **GitHub** | `.apk` | Can *Check for updates* against GitHub releases (internet used only then) |
| **Play** | `-play.aab` | No update checker (Play delivers updates) and no internet use |

Listing text, data-safety answers, the 512 px icon and the feature graphic are in
[`docs/play/`](docs/play/PLAY_STORE.md). The privacy policy is [`PRIVACY.md`](PRIVACY.md).

## Building it yourself

Every push to `main` builds the GitHub APK and the Play bundle with GitHub Actions
(`.github/workflows/build.yml`). They appear under the run's **Artifacts**, and the APK also on the `apk` branch.

Release builds are signed with a permanent key, `signing/release.p12`, so every version installs
as a normal update. The key file is protected by a long random password that is **not** in the
repository; GitHub Actions reads it from the single repository secret `KEYSTORE_PASSWORD`. Without
the password the file is useless. Without the secret, builds fall back to a temporary key.

To publish a release, add a section for the new version to [`CHANGELOG.md`](CHANGELOG.md),
bump `versionCode`/`versionName` in `app/build.gradle.kts`, then push a tag:

```sh
git tag v1.5
git push origin v1.5
```

The workflow builds the APK, creates the GitHub Release, and uses that version's
CHANGELOG section as the release notes.

To build locally, open the project in Android Studio (JDK 17, Android SDK 36), choose the `githubRelease`
or `playRelease` variant, and build; or run `gradle :app:assembleGithubRelease :app:bundlePlayRelease`.
