# Google Play listing kit

## Files
| What | File |
|---|---|
| App bundle to upload | `AnamorphicDesqueeze-vX.Y-play.aab` (attached to each GitHub release) |
| App icon (512 × 512) | `docs/play/icon-512.png` |
| Feature graphic (1024 × 500) | `docs/play/feature-graphic.png` |
| Privacy policy URL | `https://github.com/jemishmayani/Anamorphic-Desqueeze/blob/main/PRIVACY.md` (the repository must be public) |
| Screenshots | Take 2–8 phone screenshots of the Clips, Frame, Look and Export steps |

## Store listing
**App name** (max 30): `Anamorphic De-Squeeze`

**Short description** (max 80):
`De-squeeze anamorphic video from any camera. Lossless, 10-bit, log & HDR aware.`

**Full description:**
```
Shot through an anamorphic lens or adapter? Anamorphic De-Squeeze restores the true width of your footage right on your phone. It works with phones, action cams, gimbals, mirrorless and cinema cameras.

LOSSLESS DE-SQUEEZE
Tag your clip with the correct pixel aspect ratio in seconds. Your original 10-bit, log or HDR video is kept bit-for-bit, at full resolution, at any squeeze factor. Editors like DaVinci Resolve, Premiere Pro and Final Cut, and players like VLC, show it wide.

RE-ENCODE FOR SHARING
Render new, wider pixels with hardware-accelerated HEVC or H.264 so every app and website shows it de-squeezed, and optionally bake in a LUT.

FEATURES
• Squeeze presets 1.2× to 2.0×, plus any custom factor
• Vertical anamorphic: orientation and desqueeze direction controls
• Detects log profiles (D-Log, S-Log3, V-Log, Canon Log, F-Log, N-Log, Apple Log and more), HDR (HLG, HDR10, Dolby Vision), bit depth, chroma, codec and camera
• Smart recommendations explain which export to use and why
• Live preview with hold-to-compare and a filmstrip timeline
• LUT library: import .cube LUTs, live LUT preview, strength, before/after compare
• Batch export with per-clip methods, size and time estimates
• Device diagnostics: see exactly what your phone's video hardware can do
• Never crops, never touches your originals
• No accounts, no ads, no tracking, and it works offline
```

**Category:** Video Players & Editors  **Tags:** video editor, filmmaking

## App content answers
- **Privacy policy:** URL above.
- **Ads:** No ads.
- **App access:** All functionality is available without special access.
- **Content rating:** Fill in the questionnaire. The app is a utility with no user-generated sharing, so it should rate Everyone / PEGI 3.
- **Target audience:** 18+ (or 13+); not designed for children.
- **Data safety:** "No data collected" and "No data shared". The Play version makes no network requests.
- **Government / financial / health features:** None.

## Publishing notes
- A Google Play developer account is needed (one-time registration fee, plus identity verification).
- New personal developer accounts must currently run a **closed test with at least 12 testers for 14 days** before they can publish to production. Check the Play Console for the current rule.
- Enrol in **Play App Signing** (the default). Upload the `.aab`; Google re-signs it. The key in your GitHub secrets becomes your *upload key*. Keep the backup safe; if it's ever lost, Google can reset an upload key.
- Every release raises `versionCode` automatically with each new version.
