# Signing key

`release.p12` is the app's permanent signing key and the Google Play **upload key**.

- It's protected by a long random password that is **not** in this repository. GitHub Actions reads
  the password from the repository secret `KEYSTORE_PASSWORD`. Without it, the file is useless,
  so it's safe in a public repository.
- Certificate SHA-256 fingerprint: `38:C0:B0:B2:C3:60:04:A3:90:49:A1:BD:11:7B:B0:04:2F:8C:04:54:1D:5B:F1:6A:DE:0F:59:B4:0E:1D:A4:5E`
- Keep a backup of `release.p12` and the password in a password manager. With Play App Signing,
  a lost upload key can be reset through Play Console support.
