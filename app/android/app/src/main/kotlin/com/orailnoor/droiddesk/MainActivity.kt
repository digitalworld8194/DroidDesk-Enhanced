package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.ControlBridge
import com.orailnoor.droiddesk.runtime.DesktopIntegration
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
        private const val REQUEST_SHARED_STORAGE = 4100
        private const val TAG = "MainActivity"
        // Shared with the Termux control bridge so package transactions never overlap.
        private val packageOperationRunning = LinuxRuntime.packageOperationRunning
        private const val CONTROL_OWNER = "MainActivity"
    }

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var desktopIntegration: DesktopIntegration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        desktopIntegration = DesktopIntegration(this)
        // start-debian/debian-apps embed the APK's nativeLibraryDir (PROOT_LOADER),
        // which moves on every app update; rewrite them so they never go stale.
        thread(name = "proot-launchers") {
            runCatching { linuxRuntime.proot.takeIf { it.isInstalled() }?.writeLaunchers() }
                .onFailure { Log.w(TAG, "Could not refresh the PRoot launchers", it) }
        }
        // MainActivity is the HOME screen, so this keeps droiddeskctl reachable
        // even when no Linux desktop is running.
        ControlBridge.acquire(this, CONTROL_OWNER)

        if (intent.getBooleanExtra("autoSetup", false)) {
            runAutoChrootSetup()
        }
        handleHomeLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        restoreSystemBars()
        requestSharedStorageOnce()
    }

    /**
     * Debian binds /storage/emulated/0 at /mnt/phone, which needs the legacy
     * storage permission (targetSdk 28). Ask once; afterwards the user decides
     * in Android settings, so the HOME screen never nags.
     */
    private fun requestSharedStorageOnce() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return
        val prefs = getSharedPreferences("droiddesk_permissions", Context.MODE_PRIVATE)
        if (prefs.getBoolean("shared_storage_requested", false)) return
        prefs.edit().putBoolean("shared_storage_requested", true).apply()
        requestPermissions(permissions, REQUEST_SHARED_STORAGE)
    }

    override fun onDestroy() {
        ControlBridge.release(CONTROL_OWNER)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreSystemBars()
            window.decorView.post { restoreSystemBars() }
            resyncImeInsetsAfterHandoff()
        }
    }

    /**
     * Opening DroidDesk while another app's keyboard is visible hands that
     * keyboard's hide animation to this window, and Flutter can keep its start
     * inset (the bottom ~45% stays blank): its ImeSyncDeferringInsetsCallback
     * gets onPrepare for the handed-over animation but never onEnd, so it keeps
     * consuming every insets dispatch. Once the animation is over:
     * - if this window still believes the keyboard is shown although no
     *   DroidDesk text field uses it, hide it here so a complete hide
     *   animation (ending at 0) runs in this window;
     * - otherwise deliver the missing IME animation end (a no-op for Flutter
     *   when it is not waiting for one) and re-dispatch the real insets.
     */
    private fun resyncImeInsetsAfterHandoff() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val decor = window.decorView
        decor.postDelayed({
            val insets = decor.rootWindowInsets ?: return@postDelayed
            val ime = android.view.WindowInsets.Type.ime()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val imeVisible = insets.isVisible(ime)
            val typing = imm?.isAcceptingText == true
            Log.i(TAG, "IME resync: visible=$imeVisible bottom=${insets.getInsets(ime).bottom} typing=$typing")
            when {
                imeVisible && !typing -> window.insetsController?.hide(ime)
                !imeVisible && !typing -> {
                    // Flutter registers its callback on the FlutterView itself; a
                    // parent's dispatch mode can keep a decor-level end from it.
                    val target = findFlutterView(decor) ?: decor
                    target.dispatchWindowInsetsAnimationEnd(
                        android.view.WindowInsetsAnimation(ime, null, 0L),
                    )
                    target.requestApplyInsets()
                    Log.i(TAG, "IME resync: ended IME animation on ${target.javaClass.simpleName}")
                }
            }
        }, 1_000L)
    }

    private fun findFlutterView(view: android.view.View): io.flutter.embedding.android.FlutterView? {
        if (view is io.flutter.embedding.android.FlutterView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) findFlutterView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun restoreSystemBars() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeLaunch(intent)
    }

    private fun handleHomeLaunch(homeIntent: Intent) {
        if (homeIntent.action != Intent.ACTION_MAIN || !homeIntent.hasCategory(Intent.CATEGORY_HOME)) return

        thread(name = "home-launch") {
            val rooted = chrootRuntime.hasRoot()
            val desktopEnv = if (rooted) "xfce4" else linuxRuntime.getInstalledDE()
            val ready = if (rooted) {
                chrootRuntime.isRootfsReady() && chrootRuntime.isDesktopInstalled()
            } else {
                linuxRuntime.isBootstrapped() && desktopEnv.isNotEmpty()
            }
            if (!ready) {
                Log.i(TAG, "Home launch opened setup because Linux is not ready")
                return@thread
            }

            startForegroundService()
            val sessionRunning = if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning()
            runOnUiThread {
                startActivity(Intent(this, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                    putExtra("startSession", !sessionRunning)
                    putExtra("mode", if (rooted) "chroot" else "termux")
                    putExtra("de", desktopEnv)
                })
            }
        }
    }

    /**
     * Hidden developer/auto-tester path: download, extract, install, and launch
     * the chroot desktop without any Flutter UI interaction.
     */
    private fun runAutoChrootSetup() {
        thread(name = "auto-chroot-setup") {
            try {
                Log.i(TAG, "Auto-setup: checking root...")
                if (!chrootRuntime.hasRoot()) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, getString(R.string.auto_setup_requires_root), android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                startForegroundService()

                if (!chrootRuntime.isRootfsReady()) {
                    Log.i(TAG, "Auto-setup: downloading rootfs...")
                    val dlLatch = java.util.concurrent.CountDownLatch(1)
                    var dlOk = false
                    chrootRuntime.downloadRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            dlOk = progress >= 1.0
                            dlLatch.countDown()
                        }
                    }
                    dlLatch.await()
                    if (!dlOk) throw RuntimeException("Rootfs download failed")

                    Log.i(TAG, "Auto-setup: extracting rootfs...")
                    val exLatch = java.util.concurrent.CountDownLatch(1)
                    var exOk = false
                    chrootRuntime.extractRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            exOk = progress >= 1.0
                            exLatch.countDown()
                        }
                    }
                    exLatch.await()
                    if (!exOk) throw RuntimeException("Rootfs extraction failed")
                }

                if (!chrootRuntime.isDesktopInstalled()) {
                    Log.i(TAG, "Auto-setup: installing desktop environment...")
                    val inLatch = java.util.concurrent.CountDownLatch(1)
                    var inOk = false
                    chrootRuntime.installDesktopEnvironment(
                        desktopEnv = "xfce4",
                        onProgress = { progress, _ ->
                            if (progress >= 1.0 || progress < 0) {
                                inOk = progress >= 1.0
                                inLatch.countDown()
                            }
                        },
                        onLog = {}
                    )
                    inLatch.await()
                    if (!inOk) throw RuntimeException("Desktop installation failed")
                }

                Log.i(TAG, "Auto-setup: launching desktop...")
                runOnUiThread {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", !chrootRuntime.isRunning())
                        putExtra("mode", "chroot")
                        putExtra("de", "xfce4")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-setup failed", e)
                runOnUiThread {
                    android.widget.Toast.makeText(this, getString(R.string.auto_setup_failed, e.message.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Keep Android's Wireless debugging switch enabled while DroidDesk lives.
        com.orailnoor.droiddesk.runtime.WirelessAdbKeeper.start(this)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    // /proc scan and the X11 socket check block: never on the main thread.
                    thread(name = "runtime-status") {
                      try {
                        val rooted = chrootRuntime.hasRoot()
                        val desktop = if (rooted) null else linuxRuntime.desktopState()
                        val status = mapOf(
                            "isBootstrapped" to if (rooted) chrootRuntime.isRootfsReady() else linuxRuntime.isBootstrapped(),
                            // Native mode: only a desktop whose components really run
                            // and whose DISPLAY answers counts as "Escritorio activo".
                            "isRunning" to if (rooted) chrootRuntime.isRunning() else desktop!!.active,
                            "desktop" to (desktop?.toMap() ?: emptyMap<String, Any>()),
                            "hasRoot" to rooted,
                            "distro" to if (rooted) "ubuntu-chroot" else "termux-native",
                            "installedDE" to if (rooted) {
                                if (chrootRuntime.isDesktopInstalled()) "xfce4" else ""
                            } else {
                                linuxRuntime.getInstalledDE()
                            },
                            "rootfsPath" to if (rooted) chrootRuntime.getRootfsPath() else "",
                            "rootfsSizeMB" to if (rooted) chrootRuntime.getRootfsSizeMB() else 0L
                        )
                        runOnUiThread { result.success(status) }
                      } catch (error: Exception) {
                        runOnUiThread { result.error("runtime_status", error.message, null) }
                      }
                    }
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to if (chrootRuntime.hasRoot()) {
                            "Software (llvmpipe)"
                        } else {
                            linuxRuntime.getGraphicsMode()
                        },
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Root checks ──
                "checkRoot" -> {
                    thread {
                        val ok = chrootRuntime.hasRoot()
                        runOnUiThread { result.success(ok) }
                    }
                }

                "resetRootCache" -> {
                    RootShell(this).resetCache()
                    result.success(true)
                }

                // ── Chroot rootfs management (rooted) ──
                "downloadRootfs" -> {
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.downloadRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onDownloadProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "extractRootfs" -> {
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.extractRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onExtractProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.installDesktopEnvironment(
                                desktopEnv,
                                { progress, status ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onInstallProgress",
                                                mapOf("progress" to progress, "status" to status)
                                            )
                                        }
                                    }
                                    if (progress >= 1.0 || progress < 0) {
                                        success = progress >= 1.0
                                        latch.countDown()
                                    }
                                },
                                { logChunk ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onTerminalOutput",
                                                mapOf("text" to logChunk)
                                            )
                                        }
                                    }
                                }
                            )
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                // ── Native Termux desktop install (non-root fallback) ──
                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    thread {
                        linuxRuntime.setInstallLogSink { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(
                                desktopEnv,
                            ) { progress, status ->
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            }
                            runOnUiThread { result.success(ok) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    val status = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.getOptionalAppsStatus()
                    } else {
                        linuxRuntime.getOptionalAppsStatus()
                    }
                    result.success(status)
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    thread {
                        val logSink: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod(
                                        "onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                            }
                        }

                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installOptionalApp(appId, progressSink, logSink)
                        } else {
                            linuxRuntime.setInstallLogSink(logSink)
                            try {
                                linuxRuntime.installOptionalApp(appId, progressSink)
                            } finally {
                                linuxRuntime.setInstallLogSink(null)
                            }
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                // ── Native package store ──
                "searchNativePackages" -> {
                    val query = call.argument<String>("query") ?: ""
                    val limit = call.argument<Int>("limit") ?: 60
                    thread(name = "package-search") {
                        val packages = linuxRuntime.searchNativePackages(query, limit)
                        runOnUiThread { result.success(packages) }
                    }
                }

                "getInstalledNativePackages" -> {
                    thread(name = "installed-packages") {
                        val packages = linuxRuntime.getInstalledNativePackages()
                        runOnUiThread { result.success(packages) }
                    }
                }

                "installNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = false)
                }

                "removeNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = true)
                }

                "cancelNativePackageOperation" -> {
                    result.success(linuxRuntime.cancelPackageOperation())
                }

                // ── Android + Linux desktop integration ──
                "searchDesktopItems" -> {
                    val query = call.argument<String>("query") ?: ""
                    thread(name = "desktop-search") {
                        val items = desktopIntegration.search(query, chrootRuntime.hasRoot())
                        runOnUiThread { result.success(items) }
                    }
                }

                "launchDesktopItem" -> {
                    val kind = call.argument<String>("kind") ?: ""
                    val id = call.argument<String>("id") ?: ""
                    when (kind) {
                        "android" -> result.success(AndroidAppBridge.launchAndroidPackage(this, id))
                        "setting" -> {
                            AndroidAppBridge.launchSystemAction(this, id)
                            result.success(true)
                        }
                        "linux", "folder" -> {
                            thread(name = "launch-desktop-item") {
                                val rooted = chrootRuntime.hasRoot()
                                val running = if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning()
                                if (!running) {
                                    runOnUiThread { openDesktop(startSession = true) }
                                    if (rooted) chrootRuntime.waitForDesktopReady("xfce4")
                                    else linuxRuntime.waitForDesktopReady(linuxRuntime.getInstalledDE())
                                }
                                val safeId = id.takeIf { it.matches(Regex("[A-Za-z0-9_.+-]+")) }
                                val command = if (kind == "linux" && safeId != null) {
                                    "DISPLAY=:0 gtk-launch '$safeId' >/dev/null 2>&1 &"
                                } else {
                                    val folder = when (id) {
                                        "Downloads", "Documents", "Pictures" -> id
                                        else -> ""
                                    }
                                    "DISPLAY=:0 thunar \"${if (chrootRuntime.hasRoot()) "/root" else filesDir.absolutePath + "/home"}${if (folder.isEmpty()) "" else "/$folder"}\" >/dev/null 2>&1 &"
                                }
                                if (rooted) chrootRuntime.executeCommand(command)
                                else linuxRuntime.executeCommand(command)
                                runOnUiThread {
                                    if (running) openDesktop(startSession = false)
                                    result.success(true)
                                }
                            }
                        }
                        else -> result.success(false)
                    }
                }

                "getAndroidApps" -> result.success(AndroidAppBridge.listApps(this))
                "getDockPackages" -> result.success(AndroidAppBridge.getDockPackages(this))
                "saveDockPackages" -> {
                    val packages = call.argument<List<String>>("packages").orEmpty()
                    AndroidAppBridge.setDockPackages(this, packages)
                    thread(name = "sync-desktop-dock") {
                        val synced = runCatching {
                            syncAndroidDesktopIntegration()
                            true
                        }.onFailure { Log.e(TAG, "Could not refresh desktop dock", it) }
                            .getOrDefault(false)
                        runOnUiThread { result.success(synced) }
                    }
                }

                "listDesktopSnapshots" -> result.success(desktopIntegration.listSnapshots())
                "createDesktopSnapshot" -> thread(name = "create-desktop-snapshot") {
                    runCatching { desktopIntegration.createSnapshot(chrootRuntime.hasRoot()) }
                        .onSuccess { value -> runOnUiThread { result.success(value) } }
                        .onFailure { error -> runOnUiThread { result.error("snapshot", error.message, null) } }
                }
                "restoreDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    thread(name = "restore-desktop-snapshot") {
                        runCatching {
                            val rooted = chrootRuntime.hasRoot()
                            if (chrootRuntime.isRunning()) chrootRuntime.stopSession()
                            if (linuxRuntime.isRunning()) linuxRuntime.stopSession()
                            val savedPackages = desktopIntegration.restoreSnapshot(name, rooted)
                            val missingPackages = desktopIntegration.missingPackages(savedPackages, rooted)
                            if (missingPackages.isNotEmpty()) {
                                val command = "DEBIAN_FRONTEND=noninteractive apt-get " +
                                    "-o Dpkg::Options::=--force-confdef " +
                                    "-o Dpkg::Options::=--force-confold --no-upgrade install -y " +
                                    missingPackages.joinToString(" ")
                                if (rooted) {
                                    chrootRuntime.ensureMounts()
                                    try {
                                        chrootRuntime.executeCommand(command)
                                    } finally {
                                        chrootRuntime.unmountAll()
                                    }
                                } else {
                                    linuxRuntime.executeCommand(command)
                                }
                            }
                            true
                        }.onSuccess { value -> runOnUiThread { result.success(value) } }
                            .onFailure { error -> runOnUiThread { result.error("restore", error.message, null) } }
                    }
                }
                "deleteDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    result.success(runCatching { desktopIntegration.deleteSnapshot(name) }.getOrDefault(false))
                }
                "openAndroidControl" -> {
                    AndroidAppBridge.launchSystemAction(this, call.argument<String>("action") ?: "settings")
                    result.success(true)
                }

                  // ── Local AI ──
                  "getLocalAiStatus" -> {
                      thread(name = "local-ai-status") {
                          val value = runLocalAiCommand("status")
                          runOnUiThread { result.success(value) }
                      }
                  }

                  "controlLocalAi" -> {
                      val action = call.argument<String>("action") ?: ""
                      val allowed = setOf("start", "stop", "restart", "test")

                      if (action !in allowed) {
                          result.error(
                              "local_ai_action",
                              "Unsupported local AI action",
                              null,
                          )
                      } else {
                          thread(name = "local-ai-$action") {
                              val value = runLocalAiCommand(action)
                              runOnUiThread { result.success(value) }
                          }
                      }
                  }

                  "openLocalAiChat" -> {
                      runCatching {
                          startActivity(
                              Intent(
                                  Intent.ACTION_VIEW,
                                  android.net.Uri.parse("http://127.0.0.1:8080"),
                              ),
                          )
                      }.onSuccess {
                          result.success(true)
                      }.onFailure { error ->
                          result.error("local_ai_chat", error.message, null)
                      }
                  }

                // ── Start Linux session ──
                "startLinux" -> {
                    runCatching {
                        com.orailnoor.droiddesk.runtime.SdcardBridge.setup(
                            this,
                            java.io.File(filesDir, "home"),
                        )
                    }.onFailure { android.util.Log.w("MainActivity", "SdcardBridge", it) }
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "x11"
                    var width = call.argument<Int>("width") ?: 1920
                    var height = call.argument<Int>("height") ?: 1080

                    if (height > 720) {
                        val scale = 720.0 / height
                        width = (width * scale).toInt()
                        height = 720
                    }

                    startForegroundService()

                    if (chrootRuntime.hasRoot()) {
                        // Rooted fast path: chroot + LorieView
                        thread {
                            if (!chrootRuntime.isRootfsReady()) {
                                Log.w(TAG, "Chroot rootfs not ready; cannot start session")
                                runOnUiThread { result.success(false) }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", !chrootRuntime.isRunning())
                                    putExtra("mode", "chroot")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    } else {
                        // Non-root fallback: native Termux path
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            val installed = linuxRuntime.getInstalledDE()
                            val ready = installed == desktopEnv ||
                                linuxRuntime.installDesktopEnvironmentNative(desktopEnv)
                            if (!ready) {
                                Log.e(TAG, "Native Termux desktop setup failed; session was not launched")
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.native_setup_failed),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    result.success(false)
                                }
                                return@thread
                            }
                            // Reads /proc: decided here on the worker thread, not on the UI thread.
                            val sessionRunning = linuxRuntime.isRunning()
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", !sessionRunning)
                                    putExtra("mode", "termux")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    val rooted = chrootRuntime.hasRoot()
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", false)
                        putExtra("mode", if (rooted) "chroot" else "termux")
                        putExtra("de", if (rooted) "xfce4" else linuxRuntime.getInstalledDE())
                    }
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    thread(name = "stop-linux-session") {
                        if (chrootRuntime.hasRoot() || chrootRuntime.isRunning()) {
                            chrootRuntime.stopSession()
                        }
                        linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        stopForegroundService()
                        runOnUiThread { result.success(true) }
                    }
                }

                "resetLinuxInstall" -> {
                    thread(name = "reset-linux-install") {
                        linuxRuntime.resetInstall()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val output = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.executeCommand(command) { chunk ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        } else {
                            linuxRuntime.executeCommand(command) { chunk ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── Optional Linux container (PRoot), separate from the native runtime ──
                "startContainerShell" -> {
                    val backend = linuxRuntime.proot
                    thread(name = "proot-shell") {
                        val exit = backend.runShell { chunk ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onContainerOutput", mapOf("text" to chunk))
                            }
                        }
                        runOnUiThread { result.success(exit) }
                    }
                }

                "containerInput" -> {
                    val command = call.argument<String>("command") ?: ""
                    thread(name = "proot-input") {
                        val sent = linuxRuntime.proot.sendInput(command)
                        runOnUiThread { result.success(sent) }
                    }
                }

                "stopContainerShell" -> {
                    // Walks /proc and waits for the container tree: off the main thread.
                    thread(name = "proot-stop") {
                        linuxRuntime.proot.stopShell()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "requestDefaultLauncher" -> {
                    requestDefaultLauncher()
                    result.success(true)
                }

                "unsetDefaultLauncher" -> {
                    thread(name = "unset-home-role") {
                        val removed = unsetDefaultLauncher()
                        runOnUiThread {
                            if (removed) openHomeChooser() else openHomeSettings()
                            result.success(removed)
                        }
                    }
                }

                "isDefaultLauncher" -> {
                    result.success(isDefaultLauncher())
                }

                "updateStatusBarTheme" -> {
                    val isLight = call.argument<Boolean>("isLightMode") ?: false
                    runOnUiThread {
                        updateStatusBarIcons(isLight)
                    }
                    result.success(true)
                }

                "setupBootstrap" -> {
                    if (chrootRuntime.hasRoot()) {
                        // Nothing to bootstrap for chroot; rootfs handles it
                        result.success(true)
                    } else {
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            linuxRuntime.setupBootstrap()
                            runOnUiThread { result.success(true) }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun updateStatusBarIcons(isLightMode: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (isLightMode) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (isLightMode) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun runLocalAiCommand(action: String): Map<String, Any> {
        val allowed = setOf("start", "stop", "restart", "status", "test")

        if (action !in allowed) {
            return mapOf(
                "ok" to false,
                "installed" to false,
                "running" to false,
                "healthy" to false,
                "output" to "Unsupported local AI action",
            )
        }

        val controller = java.io.File(filesDir, "usr/bin/droiddesk-ai")
        val model = "Gemma 3 4B Q4_K_M"
        val url = "http://127.0.0.1:8080"

        if (!controller.isFile) {
            return mapOf(
                "ok" to false,
                "installed" to false,
                "running" to false,
                "healthy" to false,
                "model" to model,
                "url" to url,
                "output" to "droiddesk-ai is not installed",
            )
        }

        val timeout = when (action) {
            "start", "restart" -> 60_000L
            "stop", "test" -> 30_000L
            else -> 15_000L
        }

        val actionOutcome = linuxRuntime.runControlCommand(
            "\"${controller.absolutePath}\" $action",
            timeout,
        )

        val actionOutput = actionOutcome.tail.trim()

        val statusOutcome = if (action == "status") {
            actionOutcome
        } else {
            linuxRuntime.runControlCommand(
                "\"${controller.absolutePath}\" status",
                15_000L,
            )
        }

        val statusOutput = statusOutcome.tail.trim()

        val running = statusOutput.contains("IA ACTIVA")
        val healthy = statusOutput.contains("API saludable")

        val ok = when (action) {
            "status" -> actionOutcome.exitCode == 0
            "start", "restart" -> actionOutcome.exitCode == 0 && healthy
            "stop" -> !running
            "test" -> actionOutcome.exitCode == 0 &&
                actionOutput.contains("DROIDDESK IA OK")
            else -> false
        }

        return mapOf(
            "ok" to ok,
            "installed" to true,
            "running" to running,
            "healthy" to healthy,
            "model" to model,
            "url" to url,
            "exitCode" to actionOutcome.exitCode,
            "output" to actionOutput,
            "statusOutput" to statusOutput,
        )
    }

    private fun syncAndroidDesktopIntegration() {
        val rooted = chrootRuntime.hasRoot()
        if (rooted) {
            val rootfs = java.io.File(filesDir, "rootfs")
            val homeDir = java.io.File(rootfs, "root")
            AndroidAppBridge.syncLaunchers(
                context = this,
                homeDir = homeDir,
                python = java.io.File(rootfs, "usr/bin/python3"),
                sessionRoot = rootfs,
            )
            if (chrootRuntime.isRunning()) {
                chrootRuntime.executeCommand(
                    "export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session; " +
                        AndroidAppBridge.xfceDockCommand(this, homeDir) +
                        " DISPLAY=:0 xfce4-panel -r >/dev/null 2>&1 || true",
                )
            }
        } else {
            val homeDir = java.io.File(filesDir, "home")
            AndroidAppBridge.syncLaunchers(
                context = this,
                homeDir = homeDir,
                python = java.io.File(filesDir, "usr/bin/python3"),
            )
            if (linuxRuntime.isRunning()) {
                // Isolated process: never typed into whatever the terminal runs.
                linuxRuntime.runControlCommand(
                    AndroidAppBridge.xfceDockCommand(this, homeDir) +
                        " DISPLAY=:0 xfce4-panel -r >/dev/null 2>&1 || true",
                    60_000,
                )
            }
        }
    }

    private fun openDesktop(startSession: Boolean) {
        val rooted = chrootRuntime.hasRoot()
        startForegroundService()
        startActivity(Intent(this, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
            putExtra("startSession", startSession)
            putExtra("mode", if (rooted) "chroot" else "termux")
            putExtra("de", if (rooted) "xfce4" else linuxRuntime.getInstalledDE())
        })
    }

    private fun runPackageOperation(
        flutterEngine: FlutterEngine,
        result: MethodChannel.Result,
        packageName: String,
        remove: Boolean,
    ) {
        if (!packageOperationRunning.compareAndSet(false, true)) {
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                "onPackageOperationProgress",
                mapOf(
                    "progress" to -1.0,
                    "status" to "Previous package operation is still stopping. Try again.",
                ),
            )
            result.success(false)
            return
        }
        thread(name = if (remove) "package-remove" else "package-install") {
            linuxRuntime.beginPackageOperation()
            linuxRuntime.setInstallLogSink { chunk ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationLog",
                        mapOf("text" to chunk),
                    )
                }
            }
            val progress: (Double, String) -> Unit = { value, status ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationProgress",
                        mapOf("progress" to value, "status" to status),
                    )
                }
            }
            try {
                startForegroundService()
                val ok = if (remove) {
                    linuxRuntime.removeStorePackage(packageName, progress)
                } else {
                    linuxRuntime.installStorePackage(packageName, progress)
                }
                runOnUiThread { result.success(ok) }
            } catch (error: Throwable) {
                Log.e(TAG, "Package operation failed for $packageName", error)
                progress(-1.0, error.message ?: "Package operation failed")
                runOnUiThread { result.success(false) }
            } finally {
                linuxRuntime.setInstallLogSink(null)
                linuxRuntime.finishPackageOperation()
                packageOperationRunning.set(false)
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isDefaultLauncher(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(
                homeIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName == packageName
        }
    }

    private fun requestDefaultLauncher() {
        if (isDefaultLauncher()) {
            Toast.makeText(this, getString(R.string.already_default_launcher), Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun unsetDefaultLauncher(): Boolean {
        if (!isDefaultLauncher()) return true

        val rootShell = RootShell(this)
        if (rootShell.hasRoot()) {
            runCatching {
                val userId = android.os.Process.myUid() / 100000
                rootShell.exec(
                    "cmd role remove-role-holder --user $userId " +
                        "android.app.role.HOME $packageName",
                )
                repeat(10) {
                    if (!isDefaultLauncher()) return true
                    Thread.sleep(100)
                }
            }.onFailure { Log.w(TAG, "Could not remove the Home role with root", it) }
        }

        // Older Android versions may still store Home as a preferred activity
        // rather than a RoleManager holder. Apps may clear their own preference.
        runCatching { packageManager.clearPackagePreferredActivities(packageName) }
        return !isDefaultLauncher()
    }

    private fun openHomeChooser() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        startActivity(Intent.createChooser(homeIntent, getString(R.string.choose_home_app)))
    }

    private fun openHomeSettings() {
        Toast.makeText(
            this,
            getString(R.string.select_other_home_app),
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
