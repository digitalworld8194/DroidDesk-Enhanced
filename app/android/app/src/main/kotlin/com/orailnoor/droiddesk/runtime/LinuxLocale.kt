package com.orailnoor.droiddesk.runtime

/**
 * Language of the Linux session: Spanish, preferring es_US and falling back to es_ES.
 *
 * Native Termux runtime (Bionic libc): Bionic only implements the C.UTF-8 locale, so
 * setlocale(LC_ALL, "") keeps returning C.UTF-8 whatever LANG says and UTF-8 input keeps
 * working with LANG=es_US.UTF-8. Termux builds GLib/GTK/Xfce without gettext and ships no
 * .mo catalogs, so only translations embedded in .desktop/.directory files (application
 * menu entries and categories, settings manager items) can follow LANGUAGE there.
 *
 * Chroot runtime (Ubuntu, glibc): real locales are generated at install time and the
 * session picks the first available one; LC_ALL is left unset so categories follow LANG.
 */
object LinuxLocale {
    const val PREFERRED = "es_US.UTF-8"
    const val FALLBACK = "es_ES.UTF-8"

    /** GNU/GLib language priority list used to pick translations. */
    const val LANGUAGE = "es_US:es_ES:es"

    /** Ubuntu/Debian packages with Spanish translations (best effort, may be unavailable). */
    const val CHROOT_LANGUAGE_PACKAGES = "language-pack-es language-pack-gnome-es"

    /** Generates both Spanish locales inside an Ubuntu or Debian rootfs. */
    const val CHROOT_GENERATE_COMMAND =
        "locale-gen $PREFERRED $FALLBACK || " +
            "{ sed -i -E 's/^# *((es_US|es_ES)\\.UTF-8 UTF-8)/\\1/' /etc/locale.gen && locale-gen; }"

    /** Shell snippet exporting LANG/LANGUAGE with the first locale glibc actually has. */
    val chrootProfileSnippet: String = """
        # Locale: Spanish (es_US, then es_ES), C.UTF-8 if neither has been generated.
        DROIDDESK_LANG=C.UTF-8
        for candidate in $PREFERRED $FALLBACK; do
            if locale -a 2>/dev/null | grep -qix "${'$'}(echo "${'$'}candidate" | sed 's/UTF-8${'$'}/utf8/')"; then
                DROIDDESK_LANG=${'$'}candidate
                break
            fi
        done
        export LANG=${'$'}DROIDDESK_LANG
        export LANGUAGE=$LANGUAGE
        unset LC_ALL DROIDDESK_LANG candidate
    """.trimIndent()
}
