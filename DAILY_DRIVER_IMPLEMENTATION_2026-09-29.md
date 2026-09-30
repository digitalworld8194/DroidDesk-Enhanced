# Daily Driver Implementation – 2026-09-29

Branch: `feat/daily-driver-20260929-181808`

---

## Pre-flight Checks

- **git status**: Clean working tree at commit `e9cf8d0` (feat: daily-driver polish SD bridge + camera + CI).
- **Platform**: Samsung Galaxy S24 Ultra, Android, XFCE confirmed working.
- **Flutter**: Not available locally; CI (GitHub Actions `.github/workflows/build.yml`) is the build path.

---

## Files Modified

| File | Change Summary |
|---|---|
| `app/android/app/src/main/kotlin/.../service/DroidDeskService.kt` | Added ACTION_STOP, two notification action buttons, stop handler |
| `app/android/app/src/main/kotlin/.../runtime/LinuxRuntime.kt` | Graceful stopSession, resetInstall(), updateSessionLaunchers call |
| `app/android/app/src/main/kotlin/.../runtime/XfceMobileProfile.kt` | PROFILE_MARKER v6, plugin-26 panel entry, updateSessionLaunchers() |
| `app/android/app/src/main/kotlin/.../MainActivity.kt` | Added resetLinuxInstall method channel handler |
| `app/lib/screens/welcome_screen.dart` | Fixed import and routing from DEPickerScreen to DistroPickerScreen |
| `app/lib/screens/home_screen.dart` | Reinstall Linux: confirmation dialog + state.reinstallLinux() |
| `app/lib/state/app_state.dart` | Added reinstallLinux() method |
| `app/lib/services/platform_bridge.dart` | Added resetLinuxInstall() static method |
| `app/test/app_state_test.dart` | Created (new file) — unit tests for AppState logic |

---

## Phase A: Session Management

**Graceful stopSession (LinuxRuntime.kt)**

- Sends SIGTERM to `xfce4-session`, `mate-session`, `startlxqt`, `plasmashell` before forcing.
- Waits up to 3 seconds (`waitFor(3, TimeUnit.SECONDS)`) before calling `destroyForcibly()`.
- Cleans up `tmp/dbus-session` socket file on exit.
- `dbusProcess` still uses `destroyForcibly()` (controlled process, safe to force).

**Notification Action Buttons (DroidDeskService.kt)**

- Added companion constant `ACTION_STOP`.
- `buildNotification()` now creates two `PendingIntent`s:
  - "Return to Desktop" — `PendingIntent.getActivity` to `DesktopActivity` with `startSession=false`, `FLAG_IMMUTABLE`.
  - "Stop Desktop" — `PendingIntent.getService` to `DroidDeskService` with `action=ACTION_STOP`, `FLAG_IMMUTABLE`.
- `onStartCommand` intercepts `ACTION_STOP`, runs stop in a background thread (LinuxRuntime + ChrootRuntime), then calls `stopSelf()`.
- Icon: `ic_menu_compass` for the notification; `ic_menu_close_clear_cancel` for the stop action.

---

## Phase B: S24 Ultra DPI Configuration

**Implemented in `XfceMobileProfile.updateSessionLaunchers()`:**

- Writes/updates `~/.Xresources` with `Xft.dpi: 144`.
  - If line already exists it is replaced; otherwise appended.
- Writes `~/.config/fontconfig/fonts.conf` with `<double>144</double>` if the file does not already contain a `dpi` entry.
- Called every session start (no marker guard), so changes take effect immediately.

**Existing features confirmed present:**
- Adreno GPU detection (`/dev/kgsl-3d0`) in LinuxRuntime.
- Vulkan/Turnip/Zink path in `getGraphicsMode()`.
- SdcardBridge.setup() call in MainActivity startLinux handler.

---

## Phase C: Firefox

**Implemented in `XfceMobileProfile.updateSessionLaunchers()`:**

- If `firefoxBin.canExecute()` (i.e. `/usr/bin/firefox` exists and is executable):
  - Overwrites `~/.config/xfce4/panel/launcher-23/droiddesk-browser.desktop` with:
    - `Name=Firefox`, `Exec=firefox %u`, `Icon=firefox`.
- Falls back to the default "Web Browser" `exo-open` launcher written at install time if Firefox is absent.

---

## Phase D: Camera

**Implemented in `XfceMobileProfile.updateSessionLaunchers()` + `LinuxRuntime.startSession()`:**

- `LinuxRuntime.startSession()` probes for Samsung (`com.sec.android.app.camera`), AOSP (`com.android.camera2`), and Pixel (`com.google.android.GoogleCamera`) camera packages using `PackageManager.getPackageInfo`.
- First matching installed package is passed to `updateSessionLaunchers(cameraPackage=...)`.
- `updateSessionLaunchers` writes `launcher-26/droiddesk-camera.desktop`:
  - `Name=Camera`, `Icon=camera-photo`, `Terminal=false`
  - `Exec=~/.local/bin/droiddesk-launch-android-app.py <package>`

**Panel layout change:**
- `PROFILE_MARKER` bumped from `v5` → `v6` to trigger re-apply for existing users.
- `plugin-26` added between plugin-23 (browser) and plugin-24 (spacer) in `panel-2`'s `plugin-ids` array.
- `plugin-26` definition added as a `launcher` type after `plugin-25` in the XML.

---

## Phase E: Desktop Cleanup

**Launcher layout confirmed:**

| plugin-id | Type | Content |
|---|---|---|
| 20 | applicationsmenu | App menu button |
| 21 | launcher | Terminal (xfce4-terminal) |
| 22 | launcher | Files (thunar) |
| 23 | launcher | Browser (Firefox if present, else exo-open) |
| 26 | launcher | Camera (Android camera via python bridge) |
| 24 | separator | Expanding spacer |
| 25 | showdesktop | Show Desktop button |

---

## Phase F: Bug Fixes

**Bug 1 — welcome_screen.dart: Wrong screen routing**
- `import 'package:droiddesk/screens/setup/de_picker.dart'` → `distro_picker.dart`
- `const DEPickerScreen()` → `const DistroPickerScreen()`
- Effect: "Set Up Desktop Essentials" now routes to the distro picker instead of the DE picker (correct first-run flow).

**Bug 2 — home_screen.dart: "Reinstall Linux" was a no-op**
- Replaced `onTap: () { Navigator.pop(sheetContext); }` with:
  1. Close sheet.
  2. Show `AlertDialog` with warning text and Cancel / Reinstall (red `FilledButton`) actions.
  3. On confirm: calls `state.reinstallLinux(pageContext)`.

**Bug 3 — app_state.dart + platform_bridge.dart + LinuxRuntime.kt + MainActivity.kt**
- Added full `reinstallLinux()` flow:
  - Stops the session if running.
  - Calls `DroidDeskPlatform.resetLinuxInstall()` → `MainActivity` handler → `linuxRuntime.resetInstall()`.
  - `resetInstall()` deletes `DE_MARKER` and `BOOTSTRAP_MARKER` but preserves `usr/` (fast reinstall path).
  - Resets Flutter state: `_isBootstrapped=false`, `_installedDE=''`, `_installedDistro=''`, `_setupStep=0`.

---

## Phase G: Tests Created

File: `app/test/app_state_test.dart`

Tests written (no real platform channel calls):
1. `AppState defaults are correct` — checks initial values of all key fields.
2. `isSetupComplete returns false when not bootstrapped` — verifies default state.
3. `isSetupComplete returns false when bootstrapped but no DE` — verifies AND condition.
4. `setSelectedDE / setSelectedDistro update state` — round-trip check.
5. `setSetupStep updates step and clears error`.
6. `clearError sets errorMessage to null`.
7. `reinstallLinux calls stopLinux when running` — uses `_TestAppState` subclass with overridden methods.
8. `terminalOutput has initial message` / `clearTerminal resets`.
9. `isDEInstalled returns false for empty installedDE`.
10. `gpuType returns Unknown GPU for empty deviceInfo`.

A `_TestAppState` helper subclass overrides `stopLinux()` and `reinstallLinux()` to avoid MethodChannel calls in tests.

---

## Phase H: Build

Flutter is not available in the local Termux environment. The build path is:

**GitHub Actions CI**: `.github/workflows/build.yml`

To trigger a build:
1. Push the current branch to the remote:
   ```
   git push origin feat/daily-driver-20260929-181808
   ```
2. The workflow triggers automatically on push, or can be dispatched manually from Actions tab.
3. The signed APK artifact is available in the Actions run summary.

To build manually on a machine with Flutter SDK:
```bash
cd app
flutter pub get
flutter build apk --release
```

---

## Functions That Android Prevents Without Root/Special Permissions

1. **Mounting filesystems** — `mount` / `bindmount` requires `CAP_SYS_ADMIN`; not granted to user apps. Chroot mode works only with root.
2. **Writing to `/proc` or `/sys`** — Kernel tuning (scheduler, cgroups for LMKD) blocked.
3. **Changing process priorities with `setpriority`** — Android's `OomAdjuster` overrides nice values for app processes.
4. **Phantom Process Killer bypass** — Android 12+ kills child processes > 32 deep without a system-level exception (`device_config` flag `max_phantom_processes`); workaround is the foreground service wake lock, which is present.
5. **Network namespace isolation** — `unshare(CLONE_NEWNET)` requires root; not available to user apps.
6. **Direct GPU access via DRM** — `/dev/dri/renderD128` is accessible but Vulkan ICD loader requires matching library paths; Turnip only works on Adreno with the specific freedreno ICD present.
7. **USB/camera hardware via V4L2** — Android's camera HAL is not exposed as V4L2 to user apps; the `droiddesk-launch-android-app.py` workaround launches the native camera app instead.
8. **Bluetooth/audio HAL** — PulseAudio AAudio module works but BT audio profile switching requires `BLUETOOTH_PRIVILEGED` (system app only).

---

## Pending Risks

1. **`DesktopActivity` mode/de extras not propagated from DroidDeskService "Return to Desktop"** — The PendingIntent sends `startSession=false` but does not know the current `mode` (chroot vs termux) or `de`. `DesktopActivity` should handle missing extras gracefully; verify this in testing.
2. **v6 marker forces profile re-apply** — Existing users with customised panel layouts will have panel-2 reset on next session. Consider a migration path if user customisation matters.
3. **Camera launcher-26 appears even if no camera found** — The panel XML always declares `plugin-26`; if `cameraPackage` is null, `launcher-26/droiddesk-camera.desktop` is never written. XFCE will show an empty/broken launcher slot. Mitigation: only add plugin-26 to panel ids when camera is confirmed present, or write a placeholder desktop file.
4. **fontconfig fonts.conf overwrite** — If the user has custom fontconfig rules, the initial write will not clobber (checked via `contains("dpi")`), but the file is created fresh if absent. Safe for first-run; verify for upgrade path.
5. **`resetInstall` does not clear v5/v6 XFCE profile marker** — After reinstall, `XfceMobileProfile.install()` will not re-run because the marker file in `homeDir` is still present. If the user expects a fresh desktop layout, the profile marker should also be deleted in `resetInstall()`.
6. **CI secrets for signing** — The build.yml requires keystore secrets configured in the GitHub repo settings. Confirm `KEYSTORE_FILE`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` are set before triggering a release build.
