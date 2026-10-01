# DroidDesk signing and update workflow

The daily-driver DroidDesk is **`com.orailnoor.droiddesk.preview`** (label
"DroidDesk", declares HOME). The original `com.orailnoor.droiddesk` stays
installed as a backup and is never uninstalled or cleared.

## Permanent key

Every build of `com.orailnoor.droiddesk.preview` must be signed with the same
permanent key, otherwise Android refuses `adb install -r` and the only way to
install would be to uninstall (losing the app's data).

| | |
|---|---|
| Keystore | `~/.droiddesk-signing/droiddesk-release.p12` (PKCS12, alias `droiddesk`, RSA 4096, valid until 2126) |
| Passwords | `~/.droiddesk-signing/keystore.properties` (mode 600) |
| Certificate SHA-256 | `c5478ae29cb5b7cfccdcfb0de763b0d8eca16c8b90ee204731e7b8d890005ee1` |

The key is never committed or uploaded (not even as a CI secret): CI builds
with Android's throwaway debug key and the APK is re-signed locally.
**Back up `~/.droiddesk-signing/` somewhere private** — losing it means future
updates can no longer be installed over the current app.

`DROIDDESK_KEYSTORE_PROPERTIES=/path/keystore.properties` overrides the
location. A machine with Flutter and the properties file builds release APKs
signed with the permanent key directly (see `app/android/app/build.gradle.kts`).

## Updating DroidDesk

1. Edit code.
2. Push to the branch; CI (`.github/workflows/build.yml`) runs the Dart and JVM
   tests and builds `DroidDesk-Preview-release.apk` (`DROIDDESK_PREVIEW=1`).
3. Once the run succeeds:

   ```sh
   scripts/sign-and-install.sh --ci          # or: scripts/sign-and-install.sh <apk>
   ```

   It checks package, label, HOME, arm64-v8a and `libsocket_hook_variant.so`,
   signs with the permanent key, checks the certificate, checks the installed
   app has the same certificate, then runs `adb install -r` (data is kept).
   It never uninstalls anything. `scripts/apk_manifest.py <apk>` dumps the
   manifest facts on its own.
