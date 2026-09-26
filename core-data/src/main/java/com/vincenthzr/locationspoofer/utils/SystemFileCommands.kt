package com.vincenthzr.locationspoofer.utils

/** Cross-process files must use init's filesystem view, even when su inherits app isolation. */
internal object SystemFileCommands {
    private const val ROOT = "/proc/1/root"
    private const val LOCAL = "$ROOT/data/local/tmp/locationspoofer_config.json"
    private const val SYSTEM = "$ROOT/data/system/locationspoofer_config.json"

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    fun read(paths: List<String>): String {
        require(paths.isNotEmpty() && paths.all { it.startsWith("/data/") && ".." !in it.split('/') })
        // Never fall back to the isolated view: it may contain an old, apparently valid copy.
        return paths.joinToString(" || ") { "cat ${quote(ROOT + it)} 2>/dev/null" }
    }

    private val copyFunctions = """
        set -e
        umask 077
        test -d $ROOT/data/system
        install_config() {
            src="${'$'}1"; dest="${'$'}2"; owner="${'$'}3"; label="${'$'}4"
            tmp="${'$'}dest.tmp.${'$'}${'$'}"
            if cp "${'$'}src" "${'$'}tmp" && chown "${'$'}owner" "${'$'}tmp" &&
                chmod 644 "${'$'}tmp" && chcon "u:object_r:${'$'}label:s0" "${'$'}tmp" &&
                mv -f "${'$'}tmp" "${'$'}dest"; then :
            else
                rm -f "${'$'}tmp"
                echo "Config write failed: ${'$'}dest" >&2
                return 1
            fi
        }
        sync_domain() {
            domain_dir="${'$'}2"
            domain_owner="${'$'}3"
            domain_label="${'$'}4"
            if [ ! -d "${'$'}domain_dir/files" ]; then
                mkdir "${'$'}domain_dir/files" &&
                    chown "${'$'}domain_owner" "${'$'}domain_dir/files" &&
                    chmod 771 "${'$'}domain_dir/files" &&
                    chcon "u:object_r:${'$'}domain_label:s0" "${'$'}domain_dir/files" || return 1
            fi
            install_config "${'$'}1" "${'$'}domain_dir/files/locationspoofer_config.json" "${'$'}domain_owner" "${'$'}domain_label"
        }
        sync_domains() {
            domain_failed=0
            for domain in com.android.phone com.android.bluetooth; do
                dir="$ROOT/data/user_de/0/${'$'}domain"
                # A device without this service has no domain directory to synchronize.
                [ -d "${'$'}dir" ] || continue
                if [ "${'$'}domain" = com.android.phone ]; then
                    owner=1001:1001; label=radio_data_file
                else
                    owner=1002:1002; label=bluetooth_data_file
                fi
                if ! sync_domain "${'$'}1" "${'$'}dir" "${'$'}owner" "${'$'}label"; then
                    echo "Config domain sync failed: ${'$'}domain" >&2
                    domain_failed=1
                fi
            done
            return "${'$'}domain_failed"
        }
    """.trimIndent()

    fun writeGlobal(): String = copyFunctions + "\n" + """
        staging="$LOCAL.input.${'$'}${'$'}"
        trap 'rm -f "${'$'}staging"' EXIT
        cat > "${'$'}staging"
        # Publish the shared fallback before any private directory can fail.
        chmod 644 "${'$'}staging"
        chcon u:object_r:${RootManager.CONFIG_SELINUX_TYPE}:s0 "${'$'}staging" 2>/dev/null || chcon u:object_r:shell_data_file:s0 "${'$'}staging"
        mv -f "${'$'}staging" "$LOCAL"
        write_failed=0
        install_config "$LOCAL" "$SYSTEM" 1000:1000 system_data_file || write_failed=1
        # Keep the app copy owned and labelled as app data; only its contents change.
        if cp "$LOCAL" $ROOT/data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json &&
            chmod 644 $ROOT/data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json; then :
        else write_failed=1
        fi
        sync_domains "$LOCAL" || write_failed=1
        exit "${'$'}write_failed"
    """.trimIndent()

    fun syncGlobal(): String = copyFunctions + "\n" + """
        if [ -f "$SYSTEM" ]; then
            sync_domains "$SYSTEM"
        fi
    """.trimIndent()
}
