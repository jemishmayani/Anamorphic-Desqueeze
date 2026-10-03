# Security policy

## Reporting a problem

Please **don't open a public issue** for security problems. Instead, use GitHub's private
reporting: **Security → Report a vulnerability** on this repository. You'll get a reply as soon
as possible, and credit in the changelog if you'd like.

## Verifying a download

Every release from v1.7 onwards is signed with the same key. Its SHA-256 certificate fingerprint is:

```
38:C0:B0:B2:C3:60:04:A3:90:49:A1:BD:11:7B:B0:04:2F:8C:04:54:1D:5B:F1:6A:DE:0F:59:B4:0E:1D:A4:5E
```

To check an APK on a computer with the Android SDK:

```sh
apksigner verify --print-certs AnamorphicDesqueeze-vX.Y.apk
```

The "Signer #1 certificate SHA-256 digest" must match the fingerprint above (ignoring colons and
letter case). If it doesn't, the file didn't come from this project; don't install it.

Copies installed from **Google Play** are re-signed by Google (Play App Signing), so they show a
different fingerprint. That's expected; it also means a Play install and a GitHub APK can't update
each other, so stick with one source.

## Supported versions

Only the latest release receives fixes.
