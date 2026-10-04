# Contributing

Thanks for helping! Bug reports, footage samples from new cameras, and pull requests are all welcome.

## Reporting bugs
Use the **Bug report** form under Issues. Including the app's **Copy report** output (from a crash,
or Settings → Device diagnostics) usually makes a fix possible straight away.

## Footage from new cameras
Log/HDR detection reads each file's header. If your camera's log profile isn't detected, a short
clip (a few seconds is enough) shared via a link in an issue helps a lot.

## Building
- Open the project in Android Studio (JDK 17, Android SDK 36), choose the `githubRelease` or
  `playRelease` build variant, and run.
- Or from the command line: `gradle :app:assembleGithubRelease :app:bundlePlayRelease`.
- Without the signing password, builds are signed with a temporary debug key, which is fine for testing.

Every pull request is built and unit-tested automatically by GitHub Actions (nothing is published from pull requests). Run the tests locally with `gradle :app:testGithubDebugUnitTest`; changes to log/HDR detection should come with a test case in `GammaClassifierTest`.

## Code
- Kotlin + Jetpack Compose (Material 3); video through AndroidX Media3.
- Keep the app offline: no analytics, no new network calls, no new permissions without discussion.
- User-facing text is plain English and explains *why*, not just *what*.
- For user-visible changes, add a line to the next version's section in `CHANGELOG.md`; the
  release notes are generated from it.
