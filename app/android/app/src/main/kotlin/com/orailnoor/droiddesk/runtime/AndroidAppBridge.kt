package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * Makes Android launcher apps available to the Linux desktop without granting
 * the Linux processes any extra Android permissions.  XFCE entries connect to
 * this app-private Unix socket; the Android service then launches the chosen
 * activity through the normal Android API.
 */
object AndroidAppBridge {
    private const val TAG = "AndroidAppBridge"
    private const val SOCKET_NAME = "droiddesk.android-app-launcher"
    private const val ORIGINAL_PACKAGE = "com.orailnoor.droiddesk"
    /** The panel's Terminal button opens the user's real Android Termux app. */
    private const val TERMUX_PACKAGE = "com.termux"
    private val DOCK_PLUGIN_IDS = 30..37

    @Volatile private var server: LocalServerSocket? = null

    fun listApps(context: Context): List<Map<String, String>> = launcherActivities(context).map {
        mapOf(
            "label" to it.loadLabel(context.packageManager).toString(),
            "package" to it.activityInfo.packageName,
            "source" to "Android",
        )
    }

    fun launchAndroidPackage(context: Context, packageName: String): Boolean =
        launchPackage(context, packageName)

    fun getDockPackages(context: Context): List<String> {
        val preferences = context.getSharedPreferences("desktop_integration", Context.MODE_PRIVATE)
        if (preferences.contains("dock_packages")) {
            return preferences.getString("dock_packages", "").orEmpty()
                .split(',').filter(String::isNotBlank)
        }
        return defaultDockPackages(context, launcherActivities(context))
    }

    fun setDockPackages(context: Context, packages: List<String>) {
        val installed = launcherActivities(context).map { it.activityInfo.packageName }.toSet()
        val safe = packages.filter { it in installed }.distinct().take(8)
        context.getSharedPreferences("desktop_integration", Context.MODE_PRIVATE)
            .edit().putString("dock_packages", safe.joinToString(",")).commit()
    }

    fun start(context: Context) {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            try {
                val socket = LocalServerSocket(socketName(context))
                server = socket
                thread(name = "android-app-bridge", isDaemon = true) {
                    serve(context.applicationContext, socket)
                }
                Log.i(TAG, "Android app launcher bridge started")
            } catch (error: Exception) {
                Log.e(TAG, "Could not start Android app launcher bridge", error)
            }
        }
    }

    /**
     * Abstract socket names are global, so side-by-side builds (the preview)
     * need their own name; the regular app keeps the historical one.
     */
    private fun socketName(context: Context): String =
        if (context.packageName == ORIGINAL_PACKAGE) SOCKET_NAME
        else "${context.packageName}.android-app-launcher"

    fun stop() {
        synchronized(this) {
            runCatching { server?.close() }
            server = null
        }
    }

    fun syncLaunchers(context: Context, homeDir: File, python: File, sessionRoot: File? = null) {
        if (!python.canExecute()) {
            Log.w(TAG, "Python is unavailable; Android app launchers were not synced")
            return
        }
        val appsDir = File(homeDir, ".local/share/applications/droiddesk-android").apply { mkdirs() }
        appsDir.listFiles()?.forEach { it.delete() }
        val iconsDir = File(homeDir, ".local/share/icons/droiddesk-android").apply { mkdirs() }
        iconsDir.listFiles()?.forEach { it.delete() }

        val launcher = File(homeDir, ".local/bin/droiddesk-launch-android-app.py")
        fun sessionPath(file: File): String = if (sessionRoot == null) {
            file.absolutePath
        } else {
            "/" + file.relativeTo(sessionRoot).invariantSeparatorsPath
        }
        val pythonPath = sessionPath(python)
        val launcherPath = sessionPath(launcher)
        launcher.parentFile?.mkdirs()
        launcher.writeText(
            """
            #!$pythonPath
            import socket
            import sys

            if len(sys.argv) != 2:
                raise SystemExit("Expected an Android package name")
            client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            client.connect("\0${socketName(context)}")
            client.sendall((sys.argv[1] + "\n").encode())
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val activities = launcherActivities(context)

        activities.forEach { activity ->
            val packageName = activity.activityInfo.packageName
            val label = desktopEscape(activity.loadLabel(context.packageManager).toString())
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val iconFile = File(iconsDir, "$safeName.png")
            val icon = if (writeIconPng(activity.loadIcon(context.packageManager), iconFile)) {
                sessionPath(iconFile)
            } else {
                "applications-other"
            }
            val filename = "$safeName.desktop"
            File(appsDir, filename).writeText(
                """
                [Desktop Entry]
                Version=1.0
                Type=Application
                Name=$label
                Comment=Open Android app: $packageName
                Exec=$pythonPath $launcherPath $packageName
                Icon=$icon
                Terminal=false
                Categories=Utility;
                StartupNotify=false
                """.trimIndent() + "\n",
            )
        }
        val termuxIcon = File(iconsDir, "$TERMUX_PACKAGE.png").takeIf { it.isFile }
            ?.let { sessionPath(it) } ?: "utilities-terminal"
        writeTerminalPanelLauncher(homeDir, "$pythonPath $launcherPath $TERMUX_PACKAGE", termuxIcon)
        syncUtilityLaunchers(homeDir, pythonPath, launcherPath, sessionPath(homeDir))
        syncDockLaunchers(context, homeDir, activities, appsDir)
        Log.i(TAG, "Synced ${activities.size} Android app launchers into ${appsDir.absolutePath}")
    }

    private fun syncUtilityLaunchers(
        homeDir: File,
        pythonPath: String,
        launcherPath: String,
        homePath: String,
    ) {
        val appsDir = File(homeDir, ".local/share/applications/droiddesk-tools").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val folders = listOf(
            Triple("home", "Home folder", homePath),
            Triple("downloads", "Downloads", "$homePath/Downloads"),
            Triple("documents", "Documents", "$homePath/Documents"),
            Triple("pictures", "Pictures", "$homePath/Pictures"),
        )
        folders.forEach { (id, label, path) ->
            File(homeDir, id.replaceFirstChar { it.uppercase() }).takeIf { id != "home" }?.mkdirs()
            File(appsDir, "droiddesk-folder-$id.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=$label
                Comment=Open $label in Linux Files
                Exec=thunar ${desktopEscape(path)}
                Icon=folder
                Terminal=false
                Categories=Utility;FileManager;
                Keywords=files;folder;search;$id;
                """.trimIndent() + "\n",
            )
        }
        listOf(
            Triple("wifi", "Wi-Fi settings", "network-wireless"),
            Triple("bluetooth", "Bluetooth settings", "bluetooth"),
            Triple("display", "Display and brightness", "video-display"),
            Triple("sound", "Sound and volume", "audio-volume-high"),
            Triple("hotspot", "Mobile hotspot", "network-transmit-receive"),
            Triple("battery", "Battery saver", "battery"),
        ).forEach { (id, label, icon) ->
            File(appsDir, "droiddesk-setting-$id.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=$label
                Comment=Open Android $label
                Exec=$pythonPath $launcherPath action:$id
                Icon=$icon
                Terminal=false
                Categories=Settings;System;
                Keywords=phone;android;control center;$id;
                """.trimIndent() + "\n",
            )
        }
        File(appsDir, "droiddesk-linux-terminal.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Linux Terminal
            Comment=Open a terminal inside the Linux desktop
            TryExec=xfce4-terminal
            Exec=xfce4-terminal
            Icon=org.xfce.terminalemulator
            Terminal=false
            Categories=System;TerminalEmulator;
            Keywords=shell;console;linux;
            """.trimIndent() + "\n",
        )
    }

    /**
     * Rewrites the fixed panel Terminal slot (launcher-21) every sync so existing
     * profiles switch to Android Termux; the Linux terminal stays in the menu.
     */
    private fun writeTerminalPanelLauncher(homeDir: File, exec: String, icon: String) {
        val file = File(homeDir, ".config/xfce4/panel/launcher-21/droiddesk-terminal.desktop")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=Terminal
            Comment=Open Android Termux ($TERMUX_PACKAGE)
            Exec=$exec
            Icon=$icon
            StartupNotify=false
            Terminal=false
            """.trimIndent() + "\n",
        )
    }

    private fun syncDockLaunchers(
        context: Context,
        homeDir: File,
        activities: List<android.content.pm.ResolveInfo>,
        appsDir: File,
    ) {
        val byPackage = activities.associateBy { it.activityInfo.packageName }
        val dockEntries = panelDockPackages(context, homeDir).mapIndexedNotNull { index, packageName ->
            if (!byPackage.containsKey(packageName)) return@mapIndexedNotNull null
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val source = File(appsDir, "$safeName.desktop")
            if (!source.isFile) return@mapIndexedNotNull null
            val id = 30 + index
            val dockDir = File(homeDir, ".config/xfce4/panel/launcher-$id").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val dockFile = File(dockDir, "droiddesk-android-$safeName.desktop")
            source.copyTo(dockFile, overwrite = true)
            id to dockFile.name
        }

        val panelFile = File(homeDir, ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml")
        if (!panelFile.isFile) return
        val idStart = "<!-- DroidDesk Android dock ids start -->"
        val idEnd = "<!-- DroidDesk Android dock ids end -->"
        val pluginStart = "<!-- DroidDesk Android dock plugins start -->"
        val pluginEnd = "<!-- DroidDesk Android dock plugins end -->"
        var xml = stripDockEntries(
            panelFile.readText()
                .replace(managedXmlBlock(idStart, idEnd), "")
                .replace(managedXmlBlock(pluginStart, pluginEnd), ""),
        )

        val idNeedle = Regex("<value type=\\\"int\\\" value=\\\"24\\\"/>")
        idNeedle.find(xml)?.let { match ->
            val indent = "        "
            val replacement = buildString {
                append('\n').append(indent).append(idStart).append('\n')
                dockEntries.forEach { (id, _) ->
                    append(indent).append("<value type=\"int\" value=\"$id\"/>").append('\n')
                }
                append(indent).append(idEnd).append('\n').append(match.value)
            }
            xml = xml.replaceRange(match.range, replacement)
        }

        val pluginNeedle = Regex("<property name=\\\"plugin-24\\\"")
        pluginNeedle.find(xml)?.let { match ->
            val indent = "    "
            val pluginBlock = buildString {
                append('\n').append(indent).append(pluginStart).append('\n')
                dockEntries.forEach { (id, filename) ->
                    append(
                        """
                        <property name="plugin-$id" type="string" value="launcher">
                          <property name="items" type="array">
                            <value type="string" value="$filename"/>
                          </property>
                        </property>
                        """.trimIndent().prependIndent(indent),
                    )
                    append('\n')
                }
                append(indent).append(pluginEnd).append('\n')
            }
            xml = xml.replaceRange(match.range, pluginBlock + match.value)
        }
        panelFile.writeText(xml)
        Log.i(TAG, "Added ${dockEntries.size} installed Android apps to the XFCE dock")
    }

    /** Updates the active xfconf session; editing its XML file alone is not enough while XFCE is running. */
    fun xfceDockCommand(context: Context, homeDir: File): String {
        val dock = panelDockPackages(context, homeDir).mapIndexed { index, packageName ->
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            (30 + index) to "droiddesk-android-$safeName.desktop"
        }
        val camera = listOf(26).filter { cameraLauncherFile(homeDir).isFile }
        val pluginIds = listOf(20, 21, 22, 23) + camera + dock.map { it.first } + listOf(24, 25)
        return buildString {
            for (id in DOCK_PLUGIN_IDS) {
                append("xfconf-query -c xfce4-panel -p /plugins/plugin-$id -r >/dev/null 2>&1 || true; ")
            }
            append("xfconf-query -c xfce4-panel -p /panels/panel-2/plugin-ids -a ")
            pluginIds.forEach { id -> append("-t int -s $id ") }
            append(">/dev/null 2>&1; ")
            dock.forEach { (id, filename) ->
                append("xfconf-query -c xfce4-panel -p /plugins/plugin-$id -n -t string -s launcher >/dev/null 2>&1; ")
                append("xfconf-query -c xfce4-panel -p /plugins/plugin-$id/items -n -a -t string -s '$filename' >/dev/null 2>&1; ")
            }
        }
    }

    private fun managedXmlBlock(start: String, end: String): Regex = Regex(
        "${Regex.escape(start)}.*?${Regex.escape(end)}",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun cameraLauncherFile(homeDir: File) =
        File(homeDir, ".config/xfce4/panel/launcher-26/droiddesk-camera.desktop")

    /** Dock packages minus those already shown by a fixed panel launcher (Terminal, Camera). */
    private fun panelDockPackages(context: Context, homeDir: File): List<String> {
        val cameraPackage = cameraLauncherFile(homeDir).takeIf { it.isFile }
            ?.readLines()?.firstOrNull { it.startsWith("Exec=") }
            ?.substringAfterLast(' ')?.trim()
        val fixed = setOfNotNull(TERMUX_PACKAGE, cameraPackage)
        return getDockPackages(context).filterNot { it in fixed }.distinct()
    }

    /**
     * xfconfd rewrites xfce4-panel.xml without comments, so the managed markers
     * can disappear and old dock entries would be added a second time. Remove
     * every dock id from panel-2 and every dock plugin definition explicitly.
     */
    private fun stripDockEntries(xml: String): String {
        val dockValue = Regex("\\s*<value type=\"int\" value=\"(3[0-7])\"/>")
        val panel2Ids = Regex(
            "(<property name=\"panel-2\".*?<property name=\"plugin-ids\" type=\"array\">)(.*?)(</property>)",
            RegexOption.DOT_MATCHES_ALL,
        )
        var result = panel2Ids.replace(xml) { match ->
            match.groupValues[1] + match.groupValues[2].replace(dockValue, "") + match.groupValues[3]
        }
        for (id in DOCK_PLUGIN_IDS) {
            while (true) {
                val start = result.indexOf("<property name=\"plugin-$id\"")
                if (start < 0) break
                val end = propertyEnd(result, start) ?: break
                val lineStart = result.lastIndexOf('\n', start - 1)
                val from = if (lineStart >= 0 && result.substring(lineStart + 1, start).isBlank()) lineStart else start
                result = result.removeRange(from, end)
            }
        }
        return result
    }

    /** Index just past the `</property>` closing the element that opens at [start]. */
    private fun propertyEnd(xml: String, start: Int): Int? {
        var depth = 0
        for (tag in Regex("<property\\b[^>]*?(/?)>|</property>").findAll(xml, start)) {
            when {
                tag.value == "</property>" -> depth--
                tag.groupValues[1] != "/" -> depth++
            }
            if (depth == 0) return tag.range.last + 1
        }
        return null
    }

    private fun serve(context: Context, socket: LocalServerSocket) {
        while (server === socket) {
            var client: LocalSocket? = null
            try {
                client = socket.accept()
                val command = client.inputStream.bufferedReader().readLine()?.trim().orEmpty()
                if (command.startsWith("action:")) {
                    launchSystemAction(context, command.removePrefix("action:"))
                } else if (!launchPackage(context, command)) {
                    showLaunchFailure(context, command)
                }
            } catch (error: Exception) {
                if (server === socket) Log.w(TAG, "Android app launcher request failed", error)
            } finally {
                runCatching { client?.close() }
            }
        }
    }

    private fun launchPackage(context: Context, packageName: String): Boolean {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "Could not launch Android package $packageName", it) }
            .getOrDefault(false)
    }

    private fun showLaunchFailure(context: Context, packageName: String) {
        val installed = context.packageManager.getLaunchIntentForPackage(packageName) != null
        val message = when {
            packageName == TERMUX_PACKAGE && !installed ->
                context.getString(R.string.bridge_termux_missing, TERMUX_PACKAGE)
            !installed -> context.getString(R.string.bridge_app_not_installed, packageName)
            else -> context.getString(R.string.bridge_app_open_failed, packageName)
        }
        Log.w(TAG, message)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun launchSystemAction(context: Context, action: String) {
        val settingsAction = when (action) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "home_settings" -> Settings.ACTION_HOME_SETTINGS
            "samsung_home" -> {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage("com.sec.android.app.launcher")
                    ?: context.packageManager.getLaunchIntentForPackage("com.android.launcher3")
                if (launchIntent != null) {
                    runCatching {
                        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Log.w(TAG, "Could not launch Samsung Home", it) }
                } else {
                    Log.w(TAG, "No known Samsung/AOSP launcher found")
                }
                return
            }
            else -> Settings.ACTION_SETTINGS
        }
        runCatching {
            context.startActivity(Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Could not open Android setting $action", it) }
    }

    private fun launcherActivities(context: Context): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
    }

    private fun defaultDockPackages(
        context: Context,
        activities: List<android.content.pm.ResolveInfo>,
    ): List<String> {
        val installed = activities.map { it.activityInfo.packageName }.toSet()
        val packageManager = context.packageManager
        val defaultDialer = packageManager.resolveActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val defaultMessages = packageManager.resolveActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return listOf(
            listOf(defaultDialer, "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            listOf(defaultMessages, "com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging"),
            listOf("com.whatsapp", "com.whatsapp.w4b"),
            listOf("com.android.chrome"),
            listOf("com.sec.android.app.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            listOf("com.sec.android.app.launcher", "com.android.launcher3"),
        ).mapNotNull { choices ->
            choices.filterNotNull().firstOrNull { it in installed }
        }.distinct()
    }

    private fun desktopEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", " ")

    private fun writeIconPng(drawable: android.graphics.drawable.Drawable, output: File): Boolean {
        return runCatching {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            bitmap.recycle()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not export Android app icon to ${output.name}", error)
            false
        }
    }
}
