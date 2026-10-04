# Changelog

All notable changes to Anamorphic De-Squeeze. Newest first.

## v1.17 — 2026-10-05

### Fixed
- **Frame step settings couldn't be reached with tall (vertical desqueeze) clips.** v1.16 let tall previews grow so the stretch was easier to see, but on phones the preview, filmstrip and toggle then filled the whole screen. The tool bar was pushed off the bottom, and its settings panel had no room and couldn't be scrolled to. The preview area now takes at most 60% of the screen height, so the tool bar and its panel always stay visible; if the preview area needs more room, it scrolls by itself. Tall previews get a smaller boost (1.3× instead of 1.5×), so they usually fit without scrolling.

## v1.16 — 2026-10-05

### Fixed
- **Exports looked as if nothing happened, so they were easily repeated.** A Lossless export of two clips takes about a second; the progress bar then disappeared and the bottom bar went straight back to "Export 2 videos", with the results far down the page. In the screen recording this led to three exports in a row (and three copies of each file). Now:
  - A **"✓ Exported 2 videos"** panel stays until you tap **Done**. It lists each clip with its output size, shows the save folder, and has **Play** to open the result.
  - Tapping Export again while an export runs does nothing.
- **Export progress went backwards and showed the wrong clip** (e.g. 91% → 75%, "Copying 1 of 2" while on clip 2). Each export started from the previous run's percentage, and late progress updates from one clip could arrive after the next had started. Progress now starts at 0 for every export, only moves forward, and ignores updates from earlier runs. The panel shows the overall percentage plus one line per clip: waiting, its own %, ✓ with size, or ✗ with the reason.
- **Orientation and desqueeze direction applied to every clip.** They're now per clip, like squeeze, format and export method, with **Use … for all clips**.
- **The stretch badge said "0.83× ↕" for a 1.20× vertical stretch** (it divided the output shape by the input shape). It now shows the real factor and axis, e.g. "1.20× ↕" or "1.33× ↔".

### Changed
- **The stretch is easy to see.** When de-squeezed, the preview shows a dashed outline of the original (squeezed) frame with arrows to the stretched edges, fading out when you switch to Squeezed or hold to compare. Tall results (e.g. vertical desqueeze) get a taller preview that hugs the picture, instead of a thin strip in a black box.
- **Smooth orientation changes.** Changing orientation used to rebuild the player (black screen and spinner); the video view is now rotated directly, so it's instant. Filmstrip, before/after still and scopes follow the same rotation.
- **More formats, in two rows.**
  - **Cinema:** Original wide, **2.39 : 1 Scope**, **2.00 : 1** and **1.85 : 1 Flat**.
  - **Social:** 16:9 (YouTube), 4:5, 9:16 and 1:1.

### Added
- **High-resolution export: a resolution per clip** for every format: **Auto, 1080p, 1440p or 4K**.
  - **Auto** shows what it means, e.g. "Auto (4K)": 4K for cinema frames and YouTube, 1080p for social apps.
  - Cinema frames use the standard widths, so 4K 2.39 is the UHD scope size 3840 × 1606, and 9:16 at 4K is 2160 × 3840.
  - Nothing is upscaled past the clip's own resolution; the panel says when that limit applies.
  - Tips: YouTube streams 4K uploads at higher quality; Instagram and TikTok show up to 1080p.

## v1.15 — 2026-10-05

### Fixed
- **A format picked for one clip applied to every clip.** The output format (16:9, 4:5, 9:16, 1:1) was one setting for the whole batch, so choosing 9:16 on clip 2 also gave clip 1 a 9:16 format. That's why a Lossless clip with no format chosen still showed "9:16 framing only applies in Re-encode" in its compatibility check and in the pre-export warning. Each clip now has its own format and Fit/Fill, like its own squeeze and export method; clips you don't touch keep their original wide frame and get no warning. **Use … for all clips** applies one format to the whole batch.

### Changed
- **Picking a format switches the clip to Re-encode.** Only Re-encode can reframe into 16:9, 4:5, 9:16 or 1:1, so choosing one on a Lossless clip now moves that clip to Re-encode, with an amber notice that says why (and that 10-bit log becomes 8-bit) and an **Undo**.
- **Lossless with a format is shown as a conflict, in red.** If a clip ends up in Lossless with a format set (for example after **Use Lossless for all**), a red card says the format can't be made in Lossless, with **Switch to Re-encode** and **Use Wide**. The compatibility check and the pre-export warning mark it with a red ✗ instead of the amber ⚠ used for advice.
- Choosing the recommendation card's **Lossless, original wide frame** alternative now also sets that clip back to Wide, as its title says.
- Clip labels in the Export step show each clip's own method and format, e.g. "Re-encode 9:16", "Lossless", or "Lossless ✗ 9:16" for a conflict.

## v1.14 — 2026-10-05

### Changed
- **Sharper, more readable scopes.**
  - Frames are read at 384 px instead of 192.
  - Finer grids: the waveform has 192 × 128 cells (was 96 × 64), the parade 96 × 128 per colour (was 48 × 64), the vectorscope 192 × 192 (was 96 × 96), and the histogram 256 levels per channel (was 64), lightly smoothed.
  - Waveform, parade and vectorscope are drawn like a monitor's phosphor trace: brightness follows the log of the density, so bright, busy areas no longer wash out and sparse detail stays visible. They're rendered as images and scaled smoothly, with a crisp graticule (labelled 25/50/75/100 IRE) on top.
  - The number of columns always divides the frame width evenly, which avoids striping. A side-by-side comparison is in `docs/waveform-v1.14.png`.
- **Export formats have icons.** Wide, 16:9, 4:5, 9:16 and 1:1 are equal tiles, each icon drawn in that format's shape (play button for YouTube, photo for Feed, phone for Reels) with the platform underneath. **Fit** and **Fill** are icon tiles too: the picture inside bars, or overflowing the frame with crop marks.

### Added
- **Move the scope anywhere:** drag it over the video. A grip bar shows it can be moved, and the border brightens while dragging. Its position is kept when it changes size or you switch scopes. **Reset position** in the Exposure panel puts it back top-right; tap still toggles Small/Large.
- **Progress indicators wherever you wait:**
  - Reading clips: "Reading clip 2 of 5…" with a progress bar.
  - The preview, while it waits for a decoder, prepares or rebuffers.
  - The filmstrip, while thumbnails load.
  - The scope, while it warms up.
  - LUTs, while being read or prepared for the preview.
  - Size and time estimates.

### Fixed
- Importing a large `.cube` LUT no longer freezes the screen for a moment: it's now read in the background, both in the Look step and in Settings.

## v1.13 — 2026-10-04

### Fixed
- **"This phone can't play HEVC for preview" appearing at random** on clips and phones that play them fine. Three causes, all fixed:
  - **Two previews at once.** Moving between Frame and Look (the slide animation briefly shows both screens), switching clips, or changing a LUT or orientation setting started a new preview player before the old one had released its 4K 10-bit decoder. Phones have only a few of those, so the new one sometimes got none. Previews now take turns: a new one waits (normally a fraction of a second) until the previous one has let go before claiming a decoder.
  - **The filmstrip opened eight decoders.** Each of its eight thumbnails used its own frame reader, started at the same moment as the player, and re-ran every time you entered Frame or Look. Now one reader extracts all eight, it starts after the player, and thumbnails are cached per clip.
  - **Every error was reported as "can't play".** The message now appears only when the phone really has no decoder for the clip's codec and bit depth. A busy decoder is retried automatically (three times, waiting a little longer each time), then a **Retry** button appears with a message that the decoder is busy (another app or a running export may be using it).
- Preview errors are written to the diagnostics log, so a copied crash or diagnostics report shows exactly what the decoder said.

## v1.12 — 2026-10-04

### Changed
- **Guides have icons.** The Guides tool is now a grid of equal icon tiles instead of a scrolling row of text chips, so every option is visible and one tap away:
  - **Frame lines:** None, 1.85, 2.00, 2.20, 2.35, 2.39, 2.40 and 2.76, as two rows of four. Each icon draws that ratio's letterbox bars to scale.
  - **Overlays:** Action 93%, Title 90%, Thirds, Center, Crosshair and Mask, as two rows of three. Each icon shows what it draws. They switch on and off independently; Mask is greyed out until a frame-line ratio is picked.
- The **Guides** toolbar button gets the same small dot as the other tools when any guide is on, and long-pressing it shows which ones (e.g. "2.39 + thirds").

## v1.11 — 2026-10-04

### Fixed
- **Scopes could take over the screen and couldn't be turned off.** The scope panel sat inside the pinned preview, making it about 250 dp taller and pushing the tool panel (with the Off switch) off the screen. Scopes now float over the video instead, so the preview never changes size.

### Changed
- **Scopes overlay the video**, like on a camera monitor: a compact see-through scope in the top-right corner. **Tap** it to switch between small and large; **×** turns it off. False color colours the picture itself and shows a small label with ×.
- **Better scopes:**
  - **Histogram** in RGB: red, green and blue as soft overlapping fills, overall brightness as a white outline, grid lines at 0/25/50/75/100 IRE. It's scaled so a clipped sky or black border doesn't flatten everything else.
  - **Waveform** with IRE grid lines at 0, 25, 50, 75 and 100.
  - New **RGB parade**: red, green and blue waveforms side by side, for checking white balance.
  - New **Vectorscope** (BT.709): hue as angle, saturation as distance from the centre, 75% colour-bar targets and a skin-tone line.
  - **False color** as before, with a clearer legend.
- **Exposure panel:** a 3 × 2 grid of equal tiles (Off, Histogram, Waveform, Parade, Vector, False color), a Small / Large switch, live readings (shadows, median, highlights, clipped %, crushed %) and a one-line explanation of each scope. For log footage it reminds you that readings are of the flat picture.
- **Tool bar is icon-only:** equal square buttons spread evenly across the width. A small dot marks a tool that's been changed from its default (trim set, a scope on, orientation overridden, LUT chosen). Long-press an icon to see its name and value.
- **Theme:**
  - Backgrounds are neutral graphite instead of blue-tinted, so the footage is the most colourful thing on screen.
  - New **Accent colour** setting: Flare blue (default), Cyan, Mint, Violet, Rose, Mono, or **Wallpaper**, which follows your phone's Material You colours on Android 12+.
  - Every accent passes WCAG AA contrast (4.5:1) in both dark and light themes; light mode darkens each accent just enough.

### Added
- **Automated tests for the scopes** (7 cases): histogram statistics on a ramp, black and white frames, false-color zones, RGB histogram channels, the waveform's slope, the parade's channels, and the vectorscope's geometry (neutral at the centre, BT.709 red at 103°).

## v1.10 — 2026-10-04

### Fixed
- **False "Looks like log" on normal videos.** The app judged log from a single frame near the start (in practice usually the very first frame), so a normal video opening on a flat grey intro, a black screen or a title card could be labelled log. Log is now decided from metadata first; pixels are only a hint, taken from several frames through the clip.
- **"V-Log" detected in YouTube videos titled "vlog".** Free text such as a title or comment no longer counts as camera data; compact spellings like `vlog` / `dlog` are only accepted in structured camera fields.

### Changed
- **Gamma classification with confidence and reasons.** Each clip is now one of **Log, SDR / Rec.709, HLG, PQ / HDR10** or **Unknown**, with a confidence of **Confirmed, Likely, Possible** or **Unknown**, decided in this order:
  1. **HDR from the video track:** a Dolby Vision box, or transfer metadata PQ (ST 2084) / HLG (ARIB STD-B67). Confirmed.
  2. **Log named by the camera in a structured field** (for example a gamma metadata key or the camera's XML). Confirmed, with the profile, e.g. D-Log, D-Log M, S-Log3.
  3. **Picture analysis:** five frames spread through the clip, skipping the first max(3 s, 10%) and the last 5%. Black, near-uniform (grey/black cards, fades) and flat-graphic frames (title cards, animation, screen recordings) are excluded. At least three usable frames are needed, and 70% must look flat. The result is only ever **Possible** log, or **Likely** when backed by a hint (10-bit footage from a log-capable camera brand, or log mentioned in the file's text). Never Confirmed.
  4. Otherwise **SDR** (Likely when tagged BT.709/sRGB) or **Unknown**.
- **Why it was classified:** the clip's details show a "Why" list, e.g. *"Confirmed by transfer metadata: ARIB STD-B67 (HLG)."* or *"Heuristic pixel analysis; no log metadata found. Picture: 5 of 5 usable frames look flat like log (intro and ending excluded)."*
- **Badges:** confirmed log shows its profile in a solid badge; Likely/Possible log uses a dashed badge ("Likely log", "Possible log"), so a guess never looks like a fact. Uncertain SDR shows "SDR?"; no information shows "Gamma unknown".
- **LUT advice only for confirmed log.** The suggestion to import your camera's official log-to-Rec.709 LUT, and the "stays flat unless you add a LUT" note, appear only when log is confirmed by metadata. A LUT is never applied automatically.
- **Recommendations** treat 10-bit footage as worth keeping in Lossless whatever its gamma, but mention a log profile only when it's confirmed.
- **Frame and Look steps: pinned preview with tool tabs.** The preview stays fixed at the top, and the tools sit in a tab row underneath (Frame: Squeeze, Trim, Guides, Orientation, Exposure; Look: LUT, Exposure). Each tab shows its current value, and only the selected tool's panel scrolls, so you can change a setting and see the result without scrolling up and down. On tablets and in landscape, the preview stays on the left and the tabs sit on the right.

### Added
- **Automated tests** for gamma classification (19 cases), run on every build: normal Rec.709; Rec.709 with a grey intro; black and title-card intros; DJI D-Log and D-Log M named in metadata; a DJI 10-bit file without a profile name (Likely, never Confirmed); HLG; PQ / HDR10; Dolby Vision; a YouTube-style download titled "vlog"; a graded upload whose title mentions S-Log3; animation and screen recordings; a dark night scene; and that LUT advice appears only for confirmed log. The D-Log / D-Log M fixtures are synthetic; real DJI files checked so far don't record the profile name.

## v1.9 — 2026-10-04

### Added
- **Share to De-Squeeze.** In Google Photos, your gallery or a file manager, select one or more videos, tap **Share**, and choose **De-Squeeze**. They open straight in the Clips step; sharing more while the app is open adds them to the list.
- **Social formats** (Re-encode), in the Export step: **16:9** (YouTube, TVs; up to 3840 × 2160), **4:5** (Instagram/Facebook feed, 1080 × 1350), **9:16** (Reels, Shorts, TikTok, Stories, 1080 × 1920) and **1:1** (1080 × 1080), each with:
  - **Fit:** the whole wide picture with black bars.
  - **Fill:** cropped to fill the frame, with how much of the picture is kept.
  - A live preview of the result.

  Files get a suffix such as `_9x16`. These formats also stay within the phone's encoder limits, so 4K exports at large squeezes no longer need scaling. The recommendation switches to Re-encode for these formats and explains why.
- **Background export.** Exports keep running when you switch apps or lock the phone, with a progress notification (and **Cancel**), then a "finished" notification you can tap to return. Android asks once for permission to show notifications; exports work either way.
- **Trim before export** (Frame step): a range slider plus **Start here / End here** at the playhead, and **Reset**. Re-encode cuts exactly. **Lossless trims too**: it starts on the nearest keyframe at or before your in-point (usually under a second earlier) and still never re-encodes, so 10-bit log/HDR stays bit-identical. File names get `_TRIM`; size and time estimates use the trimmed length.
- **Exposure tools for log** (Frame and Look steps):
  - **Histogram** with the percentage of crushed (≤ 2 IRE) and clipped (≥ 98 IRE) pixels, and where the shadows, midtones and highlights sit.
  - **Waveform** in IRE.
  - **False color** with a legend: crushed, near black, mid grey, skin, bright, clipped.

  With a LUT on in the Look step, the tools show the graded picture. The math is tested against known images.
- **Tablet and landscape layout:** on tablets, foldables and phones in landscape, the preview sits beside the controls, and steps without video use two columns.

### Changed
- "Keep the app open while exporting" is no longer needed (see Background export).
- The compatibility check and pre-export warning also cover trims and social formats (for example "Filling 9:16 crops away 76% of the wide picture" for a 1.33× 4K clip).

## v1.8 — 2026-10-03

### Added
- **Per-clip squeeze factor.** Each clip in a batch keeps its own factor, for people who own several anamorphic adapters (e.g. clips 1–2 at 1.33×, clip 3 at 1.20×, clip 4 at 1.50×). The clip strip shows each clip's factor, and **Apply to all** copies one factor to every clip. New clips start with your default or the last factor you picked.
- **Double Desqueeze Protection.** When a clip is already tagged (for example 1.33×), the Frame step warns: "This video already contains a 1.33× desqueeze tag", shows what applying your factor on top would produce, and offers:
  - **Keep existing:** use the file's own factor.
  - **Replace tag:** use the factor picked in the app.
  - **Force anyway:** multiply the factors.
  The choice is per clip and is honoured by Lossless, Re-encode, the preview and the estimates.
- **Framing guides** in the preview (Frame and Look steps):
  - frame lines for 1.85, 2.00, 2.20, 2.35, 2.39, 2.40 and 2.76 : 1, with an optional mask outside
  - action safe (93%) and title safe (90%), following SMPTE ST 2046-1
  - rule of thirds, center marker and crosshair
  Guides are remembered between sessions and never crop the export.
- **Compatibility check** in the Export step, per clip: input (e.g. "4K 10-bit D-Log HEVC"), desqueeze, output size, your phone's encoder limit at that height, and a ✓ / ⚠ / ✗ result with plain explanations:
  - output will be scaled
  - 10-bit becomes 8-bit
  - this codec can't be decoded
  - the LUT is ignored in Lossless
  - an existing tag isn't resolved
  - HDR will be converted to SDR
- **Pre-export warning.** Pressing Export runs the check on every clip. If anything needs attention, a dialog lists it, with **Export anyway** or **Review**.
- **LUT strength marks** at 0%, 50% and 100% (tap to jump), a larger strength readout, and a **Before / After at full quality** button under the LUT controls (in addition to Compare on the video).

## v1.7 — 2026-10-03

### Fixed
- **"App not installed as package conflicts with an existing package."** Every build used to be signed with a new temporary key, so each update needed an uninstall. Releases are now signed with one permanent key, so future versions install straight over the old one. *Uninstall v1.6 or older one last time before installing v1.7.*
- The Frame and Look steps only showed the clip picked on the Clips step. A clip switcher now appears on Frame, Look and Export.

### Added
- **Per-clip recommendations and methods:** each clip gets its own recommendation on the Export step and is exported with its own method (Lossless or Re-encode). "Use … for all" and "Reset to recommended" buttons; estimates per clip plus totals.
- **About & support** in Settings:
  - Support the development (Buy Me a Coffee)
  - Check for updates (GitHub version)
  - What's new, Source code, Privacy, Open-source licenses
- **Default export method** setting: Recommended per clip, Always Lossless, or Always Re-encode.
- **Device diagnostics, more informative:**
  - a "What this means for you" summary: largest full-size squeeze for 4K and 1080p Re-encode, 10-bit preview, HDR re-encoding, live LUT preview
  - a phone card: chipset, Android version and security patch, CPU, memory, free storage, display resolution / refresh / HDR support, graphics version
  - speeds: your measured Re-encode and Lossless speeds, plus the manufacturer's rated 4K encode speed
  - Dolby Vision and VP9 decode checks
  - **Copy diagnostics** for support requests
- **Google Play ready:** targets Android 16 (API 36), Play's requirement for new apps and updates. Also:
  - an Android App Bundle (`-play.aab`) attached to each release
  - a Play version without network access
  - a privacy policy, store listing text, data-safety answers, a 512 px icon and a feature graphic (`docs/play/`)

### Changed
- **Settings redesigned:** an app header with version, then grouped cards with icons (Defaults, Re-encode, Output & looks, This phone, About & support). The LUT library has its own page.
- Smaller app: release builds are now shrunk with R8 (class names are kept so crash reports stay readable).
- Removed an unused permission (foreground service).
- Choosing a LUT no longer silently switches the export method; clips with a LUT are recommended for Re-encode instead.
- Build tools updated (Android Gradle Plugin 8.9, Gradle 8.11).

## v1.6 — 2026-10-03

### Added
- **Four-step flow:** Clips → Frame → Look → Export, with a step header you can tap and Back/Next buttons. Clips and choices carry across steps.
- **Vertical anamorphic support:**
  - **Orientation** (Auto / Horizontal / Vertical). Auto uses the file's rotation metadata; the others fix files with missing or wrong rotation.
  - **Desqueeze direction** (Auto / Horizontal / Vertical). Auto follows the lens's squeeze axis, so a clip rotated 90° is stretched vertically.
  - Lossless writes vertical pixel aspect (e.g. `100:133`) and, when orientation is overridden, a corrected rotation matrix, still without touching the media. Re-encode rotates and stretches on the GPU. Tested against ffmpeg: media bit-identical, and the rotation matrix matches ffmpeg's own.
- **LUT preview** in the new Look step:
  - LUT off / on while the video plays, using a lighter ~720p proxy.
  - Live strength.
  - **Compare:** a full-quality still with a draggable before/after divider. Its LUT math matches ffmpeg's to within 1 level out of 255.
- **Smart export recommendations:** a summary of the clip (camera, resolution, log/HDR, bit depth, codec, squeeze), a recommended method with the reason, and the alternative. Covers log/HDR/10-bit footage, 8-bit footage, clips with a LUT, and codecs this phone can't decode.
- **Size and time estimates** before export, labelled as estimates. They learn this phone's real speed from your exports.
- **Clip list with thumbnails:** duration, resolution, bit depth and log/HDR at a glance; add or remove clips.
- **Filmstrip timeline** under the preview: thumbnails with a playhead, tap or drag to seek, current and total time.
- **Device diagnostics** (replaces Export limits):
  - a ✓ / ⚠ / ✗ checklist of decode/encode support (HEVC, 10-bit HEVC, H.264, AV1, 10-bit encode), maximum frame width, 4K frame rate and bitrate, each explained in plain words
  - a squeeze check for 4K and 1080p at 1.33×–2.0× that says which squeezes Re-encode can make at full size
- Quality and codec can be changed directly in the Export step.

### Changed
- **Lossless** now also updates the track's display size, which some players (such as QuickTime) use to size the picture, so more apps show the de-squeezed shape.
- Choosing a LUT switches the export method to Re-encode, since only Re-encode can apply it.
- The preview keeps its position and play state when moving between the Frame and Look steps.

## v1.5 — 2026-10-03

### Added
- **Works with any camera.** The app is no longer DJI-specific. Clips from phones, action cams, gimbals, mirrorless and cinema cameras are analyzed the same way.
- **Automatic footage detection**, shown as badges with custom icons:
  - Log profiles: D-Log / D-Log M, S-Log2/3, V-Log, Canon Log 2/3, F-Log/F-Log2, N-Log, Apple Log, L-Log, I-Log, Samsung Log, GoPro Log, Z-Log2, Blackmagic Film, ARRI LogC and RED Log3G10, read from the file's metadata.
  - For cameras that don't record their log profile, a frame is checked for the flat log look and shown as "Looks like log" (marked as an estimate; dark night scenes aren't mistaken for log).
  - HDR (HLG, HDR10, Dolby Vision) or SDR; 8/10/12-bit; 4:2:0 / 4:2:2 / 4:4:4; color primaries; camera make and model; an existing pixel-aspect tag; audio format.
- **All details** panel: codec and profile, transfer and matrix tags, range, HDR metadata, pixel aspect, duration, file size and average bitrate.
- **New preview:**
  - the frame springs between squeezed and de-squeezed widths
  - press and hold to compare with the original
  - play/pause, mute and a scrub bar
  - live aspect-ratio readout and mode label
- **Custom default squeeze:** Settings → Default squeeze → "Custom value…" accepts any factor from 1.00× to 3.00×.
- Lossless mode now also tags ProRes, AV1, VP9, Dolby Vision, MPEG-4 and Motion JPEG video tracks.
- Brand mark next to the app title.

### Changed
- The clip card is redesigned with grouped badges and camera info; batch clips are listed inside it.
- Wording throughout is camera-neutral (LUT hint, export method description, Export limits).
- Re-encode decides whether to keep HDR from the detected HDR type rather than from a text label.

## v1.4 — 2026-10-03

### Fixed
- **Lossless export:** the source video's file handle could be closed twice. Android 10+ kills the app instantly when this happens, which may have caused crashes during export.
- **Re-encode export:** the preview player is now paused while exporting. Decoding a 4K 10-bit clip for preview and export at the same time could exhaust the phone's hardware decoders.
- Out-of-memory and other serious errors during export now show an error message instead of closing the app.
- Progress updates now always run on the UI thread.

### Added
- Crash reports now also cover native (system/codec-level) crashes, freezes (ANR) and low-memory kills, using Android's own exit records.
- Reports include the export steps that ran before the problem, plus device model and chipset, so issues can be fixed from one copied report.

## v1.3 — 2026-10-02

### Added
- **Lossless export method (new default).** It copies the video byte-for-byte and adds a pixel-aspect-ratio tag, like setting pixel aspect in DaVinci Resolve. It's instant, bit-identical (10-bit D-Log, audio, frame rate and metadata untouched), and has no resolution limit, so any squeeze works at full 4K.
- Export method switch: **Lossless** or **Re-encode**. The LUT section only appears in Re-encode mode.
- Lossless keeps MOV sources as `.mov`.
- Crash report dialog with **Copy report**, shown after an unexpected close.

### Fixed
- The app could freeze or crash during Re-encode export and while changing the squeeze factor. Encoder size checks were repeatedly building the phone's full codec list on the main thread; it's now read once and the checks run in the background.
- A failed save no longer leaves a broken half-written file in the gallery.

## v1.2 — 2026-10-02

### Fixed
- Re-encode export failed with "encoder can't handle this resolution" for 4K footage at larger squeezes. The app guessed a size the encoder didn't accept; it now asks the encoder itself, checking dimension alignment and the clip's frame rate.
- The result card said "Saved to Movies" even when the export failed.

### Added
- The requested bitrate is capped at the encoder's maximum.
- Automatic retries with safer settings if the encoder refuses: a smaller size, then the other codec, then a 1920-wide H.264 export.
- Errors now include technical details: the size, codec and bitrate attempted, and the phone's own error.
- Failed clips are shown in red; "Nothing was saved" when no clip succeeded.

## v1.1 — 2026-10-02

### Added
- App icon: an anamorphic oval bokeh with a horizontal lens-flare streak. Supports themed (monochrome) icons on Android 13+.
- **Export limits** screen (Settings → This phone): 10-bit HEVC decode support, widest frame, maximum squeeze for 4K and 1080p, maximum frame rate and bitrate, and the full codec list.
- The output size is shown before exporting, e.g. "3840 × 2160 becomes 4096 × 1732".

### Changed
- Redesigned interface: one-tap squeeze preset chips, an animated preview width when switching Squeezed/De-squeezed, a pinned Export button with live percentage, grouped settings, and refined dark and light themes.
- The home-screen label is shortened to "De-Squeeze".

### Fixed
- Choosing a squeeze factor and then selecting a video reset the squeeze factor to the default.
- Leaving and returning from Settings no longer loses the selected clips.

## v1.0 — 2026-10-02

### Added
- First release.
- Import MP4/MOV video (HEVC/H.264, including 4K and 10-bit HEVC).
- Squeeze presets 1.2×, 1.33×, 1.5×, 1.55×, 1.6×, 1.8×, 2.0×, plus custom.
- Squeezed / De-squeezed preview.
- Hardware-accelerated export with Media3 Transformer: HEVC preferred with H.264 fallback, four quality presets, original frame rate, and audio passthrough.
- LUT library: import, select, rename and delete `.cube` LUTs, with 0–100% strength.
- Batch export, default squeeze factor setting, output folder setting, and theme setting.
- The original file is never modified.
