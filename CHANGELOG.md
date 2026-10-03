# Changelog

All notable changes to Anamorphic De-Squeeze. Newest first.

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
