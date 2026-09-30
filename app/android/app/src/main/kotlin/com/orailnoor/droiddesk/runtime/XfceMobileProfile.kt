package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File

/** Installs DroidDesk's touch-friendly Ubuntu-inspired XFCE defaults once per home. */
object XfceMobileProfile {
    private const val TAG = "XfceMobileProfile"
    private const val PROFILE_MARKER = ".droiddesk-xfce-mobile-v6"
    private const val WALLPAPER_ASSET = "droiddesk/ubuntu-touch-wallpaper.jpg"

    fun install(
        context: Context,
        homeDir: File,
        wallpaperFile: File,
        wallpaperPathInSession: String = wallpaperFile.absolutePath,
    ): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            wallpaperFile.parentFile?.mkdirs()
            context.assets.open(WALLPAPER_ASSET).use { input ->
                wallpaperFile.outputStream().use(input::copyTo)
            }

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            File(xfconfDir, "xfce4-panel.xml").writeText(panelConfig())
            File(xfconfDir, "xfce4-desktop.xml").writeText(
                desktopConfig(xmlEscape(wallpaperPathInSession)),
            )
            installPanelCss(homeDir)

            val panelDir = File(homeDir, ".config/xfce4/panel")
            writeLauncher(
                File(panelDir, "launcher-21/droiddesk-terminal.desktop"),
                name = "Terminal",
                comment = "Open the Linux terminal",
                exec = "xfce4-terminal",
                icon = "org.xfce.terminalemulator",
            )
            writeLauncher(
                File(panelDir, "launcher-22/droiddesk-files.desktop"),
                name = "Files",
                comment = "Browse files",
                exec = "thunar %u",
                icon = "org.xfce.filemanager",
            )
            writeLauncher(
                File(panelDir, "launcher-23/droiddesk-browser.desktop"),
                name = "Web Browser",
                comment = "Browse the web",
                exec = "exo-open --launch WebBrowser %u",
                icon = "org.xfce.webbrowser",
            )

            marker.writeText("1\n")
            Log.i(TAG, "Installed Ubuntu-inspired XFCE mobile profile in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile", error)
            false
        }
    }

    /**
     * Updates session-specific launchers (Firefox, Camera) and DPI settings.
     * Called every session start — does not gate on a marker.
     */
    fun updateSessionLaunchers(
        homeDir: File,
        firefoxBin: File,
        cameraPackage: String?,
    ) {
        try {
            val panelDir = File(homeDir, ".config/xfce4/panel")

            // Firefox launcher (launcher-23) — replace generic browser if Firefox is installed
            if (firefoxBin.canExecute()) {
                writeLauncher(
                    File(panelDir, "launcher-23/droiddesk-browser.desktop"),
                    name = "Firefox",
                    comment = "Browse the web with Firefox",
                    exec = "firefox %u",
                    icon = "firefox",
                )
                Log.i(TAG, "Updated launcher-23 to Firefox")
            }

            // Camera launcher (launcher-26) — if a camera package is detected
            if (cameraPackage != null) {
                val launchScript = File(homeDir, ".local/bin/droiddesk-launch-android-app.py")
                writeLauncher(
                    File(panelDir, "launcher-26/droiddesk-camera.desktop"),
                    name = "Camera",
                    comment = "Open Android Camera",
                    exec = "${launchScript.absolutePath} $cameraPackage",
                    icon = "camera-photo",
                )
                Log.i(TAG, "Updated launcher-26 for camera package $cameraPackage")
            }

            // DPI configuration for S24 Ultra / high-density screens
            val xresources = File(homeDir, ".Xresources")
            val xresContent = if (xresources.exists()) xresources.readText() else ""
            val dpiLine = "Xft.dpi: 144"
            if (!xresContent.contains("Xft.dpi:")) {
                xresources.appendText("\n$dpiLine\n")
            } else {
                // Replace existing Xft.dpi line
                xresources.writeText(
                    xresContent.lines().joinToString("\n") { line ->
                        if (line.trimStart().startsWith("Xft.dpi:")) dpiLine else line
                    } + "\n"
                )
            }

            // fontconfig DPI
            val fontconfigDir = File(homeDir, ".config/fontconfig")
            fontconfigDir.mkdirs()
            val fontsConf = File(fontconfigDir, "fonts.conf")
            val dpi144Block = "<match target=\"pattern\"><edit name=\"dpi\"><double>144</double></edit></match>"
            if (!fontsConf.exists() || !fontsConf.readText().contains("dpi")) {
                fontsConf.writeText("""
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                    <fontconfig>
                      $dpi144Block
                    </fontconfig>
                """.trimIndent() + "\n")
                Log.i(TAG, "Wrote fontconfig DPI=144")
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSessionLaunchers failed (non-fatal)", e)
        }
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
        terminal: Boolean = false,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$name
            Comment=$comment
            Exec=$exec
            Icon=$icon
            StartupNotify=true
            Terminal=$terminal
            """.trimIndent() + "\n",
        )
    }

    private fun installPanelCss(homeDir: File) {
        val cssFile = File(homeDir, ".config/gtk-3.0/gtk.css")
        cssFile.parentFile?.mkdirs()
        val startMarker = "/* DroidDesk mobile panel start */"
        val endMarker = "/* DroidDesk mobile panel end */"
        val managedBlock = """
            $startMarker
            .xfce4-panel #separator-4,
            .xfce4-panel #separator-5,
            .xfce4-panel #separator-6 {
              min-width: 52px;
              padding: 0;
              margin: 0;
            }
            $endMarker
        """.trimIndent()
        val existing = if (cssFile.exists()) cssFile.readText() else ""
        val withoutOldBlock = existing.replace(
            Regex(
                Regex.escape(startMarker) + ".*?" + Regex.escape(endMarker),
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            "",
        ).trimEnd()
        cssFile.writeText(
            if (withoutOldBlock.isEmpty()) "$managedBlock\n"
            else "$withoutOldBlock\n\n$managedBlock\n",
        )
    }

    private fun panelConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-panel" version="1.0">
          <property name="configver" type="int" value="2"/>
          <property name="panels" type="array">
            <value type="int" value="1"/>
            <value type="int" value="2"/>
            <property name="dark-mode" type="bool" value="true"/>
            <property name="panel-1" type="empty">
              <property name="position" type="string" value="p=9;x=0;y=0"/>
              <property name="length" type="uint" value="100"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="0"/>
              <property name="size" type="uint" value="30"/>
              <property name="icon-size" type="uint" value="20"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="1.000000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="4"/>
                <value type="int" value="5"/>
                <value type="int" value="6"/>
                <value type="int" value="2"/>
                <value type="int" value="1"/>
                <value type="int" value="3"/>
              </property>
            </property>
            <property name="panel-2" type="empty">
              <property name="position" type="string" value="p=7;x=0;y=0"/>
              <property name="mode" type="uint" value="1"/>
              <property name="length" type="uint" value="100"/>
              <property name="length-adjust" type="bool" value="false"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="0"/>
              <property name="size" type="uint" value="52"/>
              <property name="icon-size" type="uint" value="36"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="1.000000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="20"/>
                <value type="int" value="21"/>
                <value type="int" value="22"/>
                <value type="int" value="23"/>
                <value type="int" value="26"/>
                <value type="int" value="24"/>
                <value type="int" value="25"/>
              </property>
            </property>
          </property>
          <property name="plugins" type="empty">
            <property name="plugin-1" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-2" type="string" value="tasklist">
              <property name="show-labels" type="bool" value="false"/>
              <property name="grouping" type="uint" value="1"/>
            </property>
            <property name="plugin-3" type="string" value="clock"/>
            <property name="plugin-4" type="string" value="separator">
              <property name="expand" type="bool" value="false"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-5" type="string" value="separator">
              <property name="expand" type="bool" value="false"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-6" type="string" value="separator">
              <property name="expand" type="bool" value="false"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-20" type="string" value="applicationsmenu">
              <property name="show-button-title" type="bool" value="false"/>
            </property>
            <property name="plugin-21" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-terminal.desktop"/>
              </property>
            </property>
            <property name="plugin-22" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-files.desktop"/>
              </property>
            </property>
            <property name="plugin-23" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-browser.desktop"/>
              </property>
            </property>
            <property name="plugin-26" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-camera.desktop"/>
              </property>
            </property>
            <property name="plugin-24" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-25" type="string" value="showdesktop"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun desktopConfig(wallpaperPath: String): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-desktop" version="1.0">
          <property name="last-settings-migration-version" type="uint" value="1"/>
          <property name="backdrop" type="empty">
            <property name="screen0" type="empty">
              <property name="monitorbuiltin" type="empty">
                <property name="workspace0" type="empty">
                  <property name="color-style" type="int" value="0"/>
                  <property name="image-style" type="int" value="5"/>
                  <property name="last-image" type="string" value="$wallpaperPath"/>
                </property>
              </property>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
