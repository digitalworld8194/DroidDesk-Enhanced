package com.orailnoor.droiddesk.runtime

/**
 * Shell helpers that keep Termux packages working in DroidDesk's relocated
 * prefix. Pure text (no Android APIs) so JVM tests can render and run them.
 *
 * DroidDesk installs Termux .debs whose maintainer scripts and #! lines use
 * Termux's prefix (/data/data/com.termux/files/usr), which this app cannot
 * access. libsocket_hook redirects file *operations*, but the kernel resolves
 * #! interpreters itself, so text has to be relocated:
 *  - [relocateDeb]: before dpkg runs anything, rebuild archives whose
 *    preinst/postinst/prerm/postrm/config mention the Termux prefix
 *  - [relocateShebangs]: after each transaction, fix #! lines in bin/libexec
 *    and every reference inside installed maintainer scripts
 *  - [dpkgWrapper]: the `dpkg` command, which applies both around dpkg.real
 * ELF binaries, data files and conffiles are never edited.
 */
object RelocationScripts {
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    fun relocateShebangs(prefix: String): String = """
        #!/system/bin/sh
        # Relocates Termux's prefix in files a dpkg transaction just installed
        # (argument: scan marker) or in every installed file (argument: --all).
        # Only text is edited, ELF and other binary files are never modified:
        #  - bin/ and libexec/, including symlinked scripts such as npm: the #! line
        #  - var/lib/dpkg/info maintainer scripts: every reference, because
        #    prerm/postrm run from there later with Termux's absolute paths
        old_prefix="/data/data/com.termux/files/usr"
        new_prefix="$prefix"
        bin="$prefix/bin"
        if [ "${'$'}1" = "--all" ]; then
            set --
        else
            scan_marker="${'$'}1"
            [ -f "${'$'}scan_marker" ] || exit 0
            set -- -cnewer "${'$'}scan_marker"
        fi
        for root in "$prefix/bin" "$prefix/libexec"; do
            [ -d "${'$'}root" ] || continue
            "${'$'}bin/find" "${'$'}root" \( -type f -o -type l \) "${'$'}@" 2>/dev/null |
            while IFS= read -r file; do
                [ -f "${'$'}file" ] || continue
                first_line=${'$'}("${'$'}bin/head" -n 1 "${'$'}file" 2>/dev/null)
                case "${'$'}first_line" in
                    "#!${'$'}old_prefix"*)
                        "${'$'}bin/sed" -i --follow-symlinks "1s|${'$'}old_prefix|${'$'}new_prefix|" "${'$'}file"
                        ;;
                esac
            done
        done
        info="$prefix/var/lib/dpkg/info"
        if [ -d "${'$'}info" ]; then
            "${'$'}bin/find" "${'$'}info" -type f \( -name '*.preinst' -o -name '*.postinst' \
                -o -name '*.prerm' -o -name '*.postrm' -o -name '*.config' \) "${'$'}@" 2>/dev/null |
            while IFS= read -r file; do
                if "${'$'}bin/grep" -Iq "${'$'}old_prefix" "${'$'}file" 2>/dev/null; then
                    "${'$'}bin/sed" -i "s|${'$'}old_prefix|${'$'}new_prefix|g" "${'$'}file"
                fi
            done
        fi
        exit 0
    """.trimIndent() + "\n"

    fun relocateDeb(prefix: String, tmp: String): String = """
        #!/system/bin/sh
        # droiddesk-relocate-deb ARCHIVE OUTDIR
        # Prints the archive dpkg should install. Termux packages may ship maintainer
        # scripts (preinst/postinst/prerm/postrm/config) that use Termux's absolute
        # prefix, often as their #! interpreter. preinst runs before dpkg exposes it
        # anywhere it could be patched, so such archives are rebuilt with only those
        # text scripts relocated. Data files, conffiles and ELF binaries are copied
        # untouched, and on any failure the original archive is used unchanged.
        old_prefix="/data/data/com.termux/files/usr"
        new_prefix="$prefix"
        bin="$prefix/bin"
        deb="${'$'}1"
        out_dir="${'$'}2"
        # libsocket_hook also rewrites *relative* data/data/com.termux/... paths,
        # which would make dpkg-deb extract into the live prefix instead of ${'$'}work.
        unset LD_PRELOAD
        export TMPDIR="${'$'}{TMPDIR:-$tmp}"
        case "${'$'}deb" in
            *.deb) ;;
            *) echo "${'$'}deb"; exit 0 ;;
        esac
        [ -f "${'$'}deb" ] || { echo "${'$'}deb"; exit 0; }
        needs=""
        for script in preinst postinst prerm postrm config; do
            if "${'$'}bin/dpkg-deb" --info "${'$'}deb" "${'$'}script" 2>/dev/null | "${'$'}bin/grep" -Iq "${'$'}old_prefix"; then
                needs=1
                break
            fi
        done
        [ -n "${'$'}needs" ] || { echo "${'$'}deb"; exit 0; }
        name=${'$'}("${'$'}bin/basename" "${'$'}deb" .deb)
        work="${'$'}out_dir/${'$'}name"
        rebuilt="${'$'}out_dir/${'$'}name.deb"
        "${'$'}bin/rm" -rf "${'$'}work" "${'$'}rebuilt"
        "${'$'}bin/mkdir" -p "${'$'}out_dir"
        if "${'$'}bin/dpkg-deb" -R "${'$'}deb" "${'$'}work" >&2; then
            # Android apps run with umask 077, but dpkg-deb -b requires a
            # 0755..0775 control directory and executable maintainer scripts.
            "${'$'}bin/chmod" 0755 "${'$'}work/DEBIAN"
            for script in preinst postinst prerm postrm config; do
                file="${'$'}work/DEBIAN/${'$'}script"
                [ -f "${'$'}file" ] || continue
                "${'$'}bin/chmod" 0755 "${'$'}file"
                if "${'$'}bin/grep" -Iq "${'$'}old_prefix" "${'$'}file"; then
                    "${'$'}bin/sed" -i "s|${'$'}old_prefix|${'$'}new_prefix|g" "${'$'}file"
                fi
            done
            if "${'$'}bin/dpkg-deb" --root-owner-group -Zgzip -z1 -b "${'$'}work" "${'$'}rebuilt" >&2; then
                "${'$'}bin/rm" -rf "${'$'}work"
                echo "droiddesk: relocated maintainer scripts of ${'$'}name" >&2
                echo "${'$'}rebuilt"
                exit 0
            fi
        fi
        "${'$'}bin/rm" -rf "${'$'}work" "${'$'}rebuilt"
        echo "droiddesk: could not relocate ${'$'}name; installing the original archive" >&2
        echo "${'$'}deb"
        exit 0
    """.trimIndent() + "\n"

    fun dpkgWrapper(
        prefix: String,
        tmp: String,
        dpkgReal: String,
        dpkgRoot: String,
        relocateShebangs: String,
        relocateDeb: String,
    ): String = """
        #!/system/bin/sh
        export PATH="$prefix/bin:${'$'}PATH"
        export LD_LIBRARY_PATH="$prefix/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        export LD_PRELOAD="$prefix/lib/libsocket_hook.so${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
        # dpkg requires admindir to be inside root. Strip any caller-provided
        # --root/--admindir (and their values) and prepend our own before any
        # trailing filenames/apt separators so dpkg parses them as options.
        caller_dir="${'$'}PWD"
        args=""
        installs=""
        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                --admindir=*|--root=*)
                    ;;
                --admindir|--root)
                    shift
                    ;;
                *)
                    arg="${'$'}1"
                    case "${'$'}arg" in
                        -i|--install|--unpack)
                            installs=1
                            ;;
                    esac
                    case "${'$'}arg" in
                        /*)
                            ;;
                        *)
                            if [ -e "${'$'}caller_dir/${'$'}arg" ]; then
                                arg="${'$'}caller_dir/${'$'}arg"
                            fi
                            ;;
                    esac
                    args="${'$'}args ${'$'}arg"
                    ;;
            esac
            shift
        done
        cd "$prefix" || exit 1
        # Archives whose maintainer scripts still use Termux's prefix are rebuilt
        # with relocated scripts before dpkg runs any of them (see droiddesk-relocate-deb).
        relocated_dir="$tmp/dpkg-relocated-${'$'}${'$'}"
        if [ -n "${'$'}installs" ] && [ -x "$relocateDeb" ]; then
            relocated_args=""
            for arg in ${'$'}args; do
                case "${'$'}arg" in
                    *.deb)
                        relocated=${'$'}("$relocateDeb" "${'$'}arg" "${'$'}relocated_dir")
                        [ -n "${'$'}relocated" ] && arg="${'$'}relocated"
                        ;;
                esac
                relocated_args="${'$'}relocated_args ${'$'}arg"
            done
            args="${'$'}relocated_args"
        fi
        scan_marker="$tmp/dpkg-shebang-scan-${'$'}${'$'}"
        : > "${'$'}scan_marker"
        "$dpkgReal" --force-not-root --force-script-chrootless --root="$dpkgRoot" --admindir="$dpkgRoot/var/lib/dpkg" ${'$'}args
        status=${'$'}?
        "$relocateShebangs" "${'$'}scan_marker"
        rm -f "${'$'}scan_marker"
        rm -rf "${'$'}relocated_dir"
        exit ${'$'}status
    """.trimIndent()
}
