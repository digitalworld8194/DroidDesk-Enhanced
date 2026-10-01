# Termux control bridge (`scripts/droiddeskctl`)

Normal Termux is the **control centre**; DroidDesk is the **private runtime and
visual PC desktop**; Android is the phone's base system. `droiddeskctl` lets
Termux control, diagnose, repair and change DroidDesk's private Linux runtime
without opening the terminal inside the desktop.

```
Termux ──droiddeskctl──▶ 127.0.0.1:47822 ──adb forward──▶ DroidDesk 127.0.0.1:47821 ──▶ ControlBridge ──▶ private runtime
   (token, ~/.config/droiddesk/token)                       (loopback inside Android)   (fixed action list)
```

ControlBridge listens on Android's loopback only. Termux reaches it through an
`adb forward` that `droiddeskctl` checks before every operation:

* `adb devices` must list exactly one device in state `device`; with none (or
  only offline/unauthorized ones, or no `adb`) it stops with **ADB REQUIRED**.
  With several, choose one with `--serial` (or `DROIDDESK_ADB_SERIAL` /
  `ANDROID_SERIAL`).
* If `adb forward --list` already shows `<serial> tcp:47822 tcp:47821` it is
  reused; otherwise `adb -s <serial> forward tcp:47822 tcp:47821` creates (or
  re-points) it and the list is checked again.
* Nothing is exposed to Wi-Fi/LAN: adb's listener is on Termux's 127.0.0.1 and
  the server keeps binding `127.0.0.1` and refusing non-loopback peers.
* Ports: `--port`/`DROIDDESK_PORT` (DroidDesk side, 47821 for the preview),
  `--local-port`/`DROIDDESK_LOCAL_PORT` (Termux side, 47822 for the preview,
  47830 for the original package). `--direct`/`DROIDDESK_DIRECT=1` skips adb.
* Android's cached-app freezer can freeze DroidDesk in the background; a frozen
  DroidDesk accepts nothing, so the client reports "no responde" after 15 s.
  Bring DroidDesk to the foreground (or keep its desktop session running) first.

## Quick start

```sh
cd ~/DroidDesk-Enhanced
./scripts/droiddeskctl pair        # DroidDesk shows a 6-digit code; type it in Termux
./scripts/droiddeskctl status      # runtime, desktop, socket hook, real node/npm state
./scripts/droiddeskctl doctor      # full health check
```

The default target is the daily driver `com.orailnoor.droiddesk.preview`
(port 47821). Use `--package com.orailnoor.droiddesk` or
`DROIDDESK_PACKAGE=…` for another build (the original app uses port 47820, but
only builds that contain the bridge answer).

## Commands

| Command | What it does |
|---|---|
| `pair` / `unpair` | Pair with DroidDesk / revoke the token there and delete it here |
| `ping` | Is DroidDesk answering? (no token) |
| `status` | Bootstrap, desktop, session, graphics, `libsocket_hook.so`, **`node -v` and `npm -v` actually executed**, dpkg state of nodejs/npm/c-ares |
| `doctor` | Termux-side checks (token permissions, `am`) plus DroidDesk checks: hook prefix, dpkg wrapper, relocation helpers, `dpkgroot`, maintainer scripts and shebangs still pointing at Termux, `dpkg --audit`, node, npm, python3, xclip, shared storage, loopback binding, AndroidAppBridge |
| `shell-info` | Paths, UID, runtime environment variables |
| `logs [app\|audit] [N]` | DroidDesk's own logcat entries, or the control audit log |
| `open` | Open DroidDesk's main screen (`am start` from Termux) |
| `start-desktop` | Open the Linux desktop; starts the session if it is not running (HOME intent → `MainActivity.handleHomeLaunch`) |
| `stop-desktop` | Stop the session, the X server and the foreground service (like "stop Linux" in the app) |
| `restart-desktop` | `stop-desktop`, then `start-desktop` |
| `launch <package>` | DroidDesk validates the package against installed launcher apps; Termux starts it with `am start` |
| `settings <action>` | `wifi bluetooth display sound hotspot battery home_settings settings samsung_home` (same actions as the XFCE launchers; `samsung_home` is the emergency return to Samsung Home) |
| `exec -- <command…>` | Run a shell command inside the runtime (bash, DroidDesk environment), streamed, real exit code |
| `node` / `npm` / `npx …` | Run Node tools inside the runtime; arguments are passed literally |
| `install nodejs\|code_oss\|firefox\|imagemagick` | DroidDesk's own install flow (see below), verified afterwards |
| `repair-packages` | Repair dpkg/apt state, Node.js and npm, without upgrades |
| `sync-apps` | Regenerate the Android app launchers and the XFCE dock (reloads the panel when the desktop runs) |
| `sync-storage` | Recreate the Android storage links (DCIM, Pictures, Downloads, …) in the Linux home |

Options: `--timeout S` (exec default 300 s, max 3600; installs/repairs up to
7200), `--force`, `--json`, `--package`, `--port`, `--local-port`, `--serial`,
`--direct`.

Exit codes: the command's own exit code for `exec`/`node`/`npm`/`npx`; 124
timeout; 64 usage, blocked without `--force` or several adb devices without
`--serial`; 69 DroidDesk not reachable, ADB REQUIRED or adb forward failed;
75 another package operation running; 76 protocol/identity failure; 77 not
paired or token rejected.

## Security model

* **Loopback only.** The server binds the literal `127.0.0.1` (never `0.0.0.0`
  or `::`), checks the bound address is loopback and drops non-loopback
  peers. It is never reachable from Wi-Fi/LAN.
* **No new Android entry points.** No exported service, receiver or provider
  was added; `MainActivity` (launcher/HOME) remains the only exported
  component (enforced by `ManifestExportTest`).
* **Pairing.** `pair` makes DroidDesk show a 6-digit code (notification and
  toast). Codes expire after 120 s and allow 5 attempts; at most 5 pairing
  requests per 10 minutes; 10 failures lock pairing for 15 minutes. Another
  app that reaches the port cannot pair without the user typing the code.
* **Token.** 256 random bits, returned once, stored by Termux in
  `~/.config/droiddesk/token` (`chmod 600`, directory `700`; the client
  repairs looser permissions). DroidDesk stores **only its SHA-256** in its
  private `SharedPreferences`. Pairing again replaces (revokes) the old token;
  `unpair` revokes it explicitly.
* **The token never goes to an impostor.** If DroidDesk is not running, another
  app could listen on the port. So for every authenticated request the server
  first proves it holds the token hash —
  `HMAC-SHA256(SHA-256(token), "droiddesk-ctl-server|<client nonce>|<action>")`
  — and the client only sends the token after verifying that proof.
* **Fixed action list.** Unknown actions are rejected before authentication.
  Arguments are validated per action (package names, settings actions,
  installable apps, log kinds, timeouts, sizes).
* **`exec` guard rails.** The authenticated caller is the device owner, so this
  is not a sandbox, but: `pm clear/uninstall`, recursive deletes of `/`, `~`,
  `$HOME`, `$PREFIX` or the app's `files`/`usr`/`home`/`rootfs` directories,
  `mkfs`, `dd of=/dev/…` and `reboot` are always refused; mass upgrades
  (`upgrade`, `full-upgrade`, `dist-upgrade`) and package removals need
  `--force` (checked by the client and again by DroidDesk). Commands run with a
  timeout, never become the in-app terminal's "active command" and are killed
  (process tree) on timeout or when the client disconnects.
* **Audit log.** Every request is logged to
  `files/control/audit.log` (JSON lines: time, action, arguments — `exec`
  truncated to 300 characters —, outcome, exit code, duration; rotated at
  512 KB). Tokens and pairing codes are never logged. Read it with
  `droiddeskctl logs audit`.
* **AndroidAppBridge hardening.** The XFCE → Android app launcher uses an
  abstract Unix socket, which any app could connect to. It now checks
  `getPeerCredentials()` and only accepts DroidDesk's own UID (its Linux
  processes) and root (rooted chroot sessions).

## Lifecycle

`MainActivity` (the HOME screen) and `DroidDeskService` (the desktop session)
each hold the bridge (`ControlBridge.acquire/release`); it runs while either
is alive, so Termux reaches DroidDesk with or without a desktop session. If
Android killed both, `droiddeskctl open` brings DroidDesk back.

## Node.js, npm and Termux-prefix relocation

DroidDesk runs Termux packages from `/data/user/0/<pkg>/files/usr`.
`libsocket_hook.so` redirects file operations from Termux's prefix, but the
kernel resolves `#!` interpreters itself, so Termux maintainer scripts such as
nodejs's `preinst` and npm's `postinst` (`#!/data/data/com.termux/files/usr/bin/sh`)
fail. This used to leave npm unpacked/half-configured.

The fix is generic (`runtime/RelocationScripts.kt`, installed as
`$PREFIX/bin/droiddesk-relocate-*` and the `dpkg` wrapper):

1. **Before dpkg** (`dpkg -i/--unpack`, including every apt transaction), the
   wrapper passes each `.deb` through `droiddesk-relocate-deb`. If any of its
   `preinst/postinst/prerm/postrm/config` mentions Termux's prefix, the archive
   is rebuilt (unhooked `dpkg-deb -R/-b`) with only those text scripts
   relocated. Data files, `conffiles`, `md5sums` and ELF binaries are not
   touched; on any failure the original archive is installed.
2. **After dpkg**, `droiddesk-relocate-shebangs` fixes `#!` lines in `bin/` and
   `libexec/` (following symlinks such as `npm → npm-cli.js`) and every
   reference inside installed maintainer scripts (`prerm`/`postrm` run later).
   `--all` re-scans everything (used by `repair-packages`).
3. `patchShebangs()` now skips ELF and other binary files.
4. `ensureSocketHookPrebuilt()` replaces a `libsocket_hook.so` compiled for
   another prefix with the APK's matching variant (atomic rename, so running
   processes keep their mapping).

`install nodejs` uses `installRelocatedNodejs()` (never a bare
`pkg install nodejs`), whose `dpkg-deb` now runs without the hook (the hook
rewrote relative `data/data/com.termux/…` paths into the live prefix; the old
hooked commands remain as fallback) and relocates only maintainer scripts.
Then npm is installed, and success means `node -v` and `npm -v` actually
print versions.

`repair-packages` refreshes the wrappers, runs a full relocation pass,
`dpkg --configure -a`, repairs nodejs (relocated reinstall) and npm when they
are broken, runs `apt-get --fix-broken install` only if `dpkg --audit` still
reports problems, and finally reports `dpkg --audit`, `node -v` and `npm -v`.
It never upgrades, purges or reinstalls the runtime.

## Tests

`app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/control/`
covers the protocol, allowed/rejected actions, authentication, invalid
tokens, pairing limits, revocation, timeouts and exit codes, status and
Node/npm verdicts, AndroidAppBridge peer policy, bridge lifecycle, loopback
binding, exported components, the relocation helpers against real
`dpkg-deb`, and the real `droiddeskctl` client against a real server
(including refusing to send the token to an impostor). They run in CI with
`./gradlew :app:testReleaseUnitTest`.
