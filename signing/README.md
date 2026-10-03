# Signing key

`release.p12` is the app's permanent signing key (and the Google Play **upload key**).
It's protected by a long random password that is **not** in this repository; GitHub Actions
reads it from the repository secret `KEYSTORE_PASSWORD`. Without that password the file is useless,
so it's safe even if the repository is public.

Keep a backup of `release.p12` and the password somewhere safe (a password manager).
With Play App Signing, a lost upload key can be reset through Play Console support.
