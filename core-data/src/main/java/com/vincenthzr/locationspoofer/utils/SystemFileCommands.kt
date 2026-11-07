package com.vincenthzr.locationspoofer.utils

/** Root is used for diagnostics and one-time legacy cleanup, never configuration delivery. */
internal object SystemFileCommands {
    private const val ROOT = "/proc/1/root"
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    fun read(paths: List<String>): String {
        require(paths.isNotEmpty() && paths.all { it.startsWith("/data/") && ".." !in it.split('/') })
        return paths.joinToString(" || ") { "cat ${quote(ROOT + it)} 2>/dev/null" }
    }

    fun removeLegacyConfigs(): String = """
        set -e
        rm -f $ROOT/data/local/tmp/locationspoofer_config.json \
            $ROOT/data/local/tmp/locationspoofer_config_tmp.json \
            $ROOT/data/local/tmp/locationspoofer_config.json.input.* \
            $ROOT/data/local/tmp/.lsp_selinux_probe \
            $ROOT/data/local/tmp/.lsp_sepolicy_apply.sh \
            $ROOT/data/system/locationspoofer_config.json \
            $ROOT/data/system/locationspoofer_config_tmp.json \
            $ROOT/data/system/locationspoofer_config.json.tmp.* \
            $ROOT/data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json
        for domain in com.android.phone com.android.bluetooth; do
            rm -f "$ROOT/data/user_de/0/${'$'}domain/files/locationspoofer_config.json" \
                "$ROOT/data/user_de/0/${'$'}domain/files/locationspoofer_config.json.tmp" \
                $ROOT/data/user_de/0/${'$'}domain/files/locationspoofer_config.json.tmp.*
        done
    """.trimIndent()
}
