package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class RootManager {

    companion object {
        private const val TAG = "LocationSpoofer"

        /** 承载跨进程配置文件的专属 SELinux type，不再蹭 shell_data_file/system_data_file 这类通用类型 */
        const val CONFIG_SELINUX_TYPE = "locationspoofer_config_file"

        /**
         * 需要读取配置文件的域。
         * untrusted_app_all 是 AOSP 定义的属性，会自动覆盖所有 untrusted_app_NN 变体
         * （包括未来新增的 API level），比手动枚举 _25/_27/_29 更可靠——
         * 实测枚举法漏掉了 untrusted_app_34（见 avc denied 日志），改用属性后天然向前兼容。
         * gmscore_app 是 Google Play 服务的专属域，不属于 untrusted_app 家族，需要单独授权。
         */
        private val SEPOLICY_READ_DOMAINS = listOf(
            "untrusted_app_all",
            "untrusted_app",
            "gmscore_app",
            "platform_app",
            "system_app"
        )

        /**
         * 各 root 方案的 live sepolicy patch 工具，按顺序探测，谁能用就用谁。
         *
         * Magisk 官方文档写明 magiskpolicy 是**独立二进制**（别名 supolicy），不是 magisk 主程序的
         * applet（magisk 只有 su / resetprop 两个 applet），因此不存在 `magisk magiskpolicy` 这种调用。
         * 而现代 Magisk 的 su shell PATH 里不一定有它，必须补上 /data/adb/magisk 下的绝对路径，
         * 否则在 Magisk 上会探测不到任何工具、一条规则都下发不了。
         */
        private val SEPOLICY_TOOL_PROBES = listOf(
            "magiskpolicy --live" to "command -v magiskpolicy",
            "supolicy --live" to "command -v supolicy",
            "/data/adb/magisk/magiskpolicy --live" to "[ -x /data/adb/magisk/magiskpolicy ]",
            "ksud sepolicy patch" to "command -v ksud",
            "/data/adb/ap/bin/magiskpolicy --live" to "[ -x /data/adb/ap/bin/magiskpolicy ]"
        )
    }

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val hasRoot = executeCommand("id").contains("uid=0(root)")
        if (hasRoot) {
            applyRootBackgroundExemptions()
            ensureSepolicyRules()
        }
        hasRoot
    }

    suspend fun applyRootBackgroundExemptions(packageName: String = "com.suseoaa.locationspoofer"): Boolean =
        withContext(Dispatchers.IO) {
            val cmds = """
            chmod 755 /data/local/tmp 2>/dev/null || true
            chmod 755 /data/local 2>/dev/null || true
            dumpsys deviceidle whitelist +$packageName 2>/dev/null || true
            cmd appops set $packageName RUN_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $packageName WAKE_LOCK allow 2>/dev/null || true
            cmd appops set $packageName AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null || true
            am set-standby-bucket $packageName active 2>/dev/null || true
        """.trimIndent()
            val result = executeCommand(cmds)
            result != "ERROR"
        }

    /**
     * 为跨进程配置文件动态打入 live SELinux 策略：新建专属 type，只放行需要读它的域。
     * 取代旧的"chmod 666 + 蹭 shell_data_file/system_data_file 通用类型"方案。
     * 探测不到任何工具时记录日志、不做兜底降级。
     *
     * 规则不能作为内联 CLI 参数拼进 `su -c "..."` 字符串下发：实测在 KernelSU 上，
     * 带空格/花括号的引号参数经过 su -c 转发后会被拆成多个参数，导致 ksud/magiskpolicy
     * 把规则解析错(报 "unexpected argument")。改为把规则写成脚本文件、用 sh 执行该文件，
     * 规则内容不再经过任何命令行参数层，从根上避免这类跨 shell 转发丢引号的问题。
     */
    suspend fun ensureSepolicyRules(): Boolean = withContext(Dispatchers.IO) {
        val tool = SEPOLICY_TOOL_PROBES.firstOrNull { (_, probe) -> toolAvailable(probe) }?.first
        if (tool == null) {
            android.util.Log.w(
                TAG,
                "未找到可用的 sepolicy 工具（magiskpolicy/supolicy/ksud），配置文件可能无法被目标应用读取"
            )
            return@withContext false
        }

        // 属性集合必须写成花括号 + 空格分隔，绝对不能用逗号：
        // Magisk 官方文档中 `type type_name ^(attribute)` 的 (^) 定义为"用 {} 括起、空格分隔的集合"，
        // KernelSU 的解析器(ksud/src/sepolicy.rs)同样只认 `{ a b }` / 单个词 / `*`，两边都不支持逗号。
        // 旧的 `file_type,data_file_type` 写法在 ksud 上被容错吞掉(只有第一个属性生效、退出码仍为 0)，
        // 在 magiskpolicy 上则整条语句解析失败 —— 这正是 Magisk 用户必须开 SELinux 宽容模式的根因。
        // 属性也不能省略：Magisk 文档写明省略时默认套用 domain 属性，那是给进程域用的，不是文件类型。
        val typeRule = "type $CONFIG_SELINUX_TYPE { file_type data_file_type }"
        // 再补一条 typeattribute：万一某个工具建出了 type 却没吃进内联属性，这条能把属性补齐；
        // 属性已存在时它失败也无所谓，不影响结果判定。
        val typeAttrRule = "typeattribute $CONFIG_SELINUX_TYPE { file_type data_file_type }"
        // 每个域名单独下发一条 allow 语句，而不是合并成一条 allow { a b c }：
        // 后者只要有一个域名在当前 ROM/API level 上不存在就会导致整条规则失败,
        // 拆开后单个域名解析失败只影响它自己那一条。
        val allowRules = SEPOLICY_READ_DOMAINS.map { domain ->
            "allow $domain $CONFIG_SELINUX_TYPE file { read open getattr }"
        }

        val probePath = "/data/local/tmp/.lsp_selinux_probe"
        val script = buildString {
            appendLine("$tool '$typeRule' >/dev/null 2>&1")
            appendLine("echo TYPE_EXIT:\$?")
            appendLine("$tool '$typeAttrRule' >/dev/null 2>&1")
            appendLine("echo ATTR_EXIT:\$?")
            allowRules.forEachIndexed { index, rule ->
                appendLine("$tool '$rule' >/dev/null 2>&1")
                appendLine("echo ALLOW_${index}_EXIT:\$?")
            }
            // 端到端验证：真的 chcon 一个探针文件再把标签读回来，确认这个 type 确实存在、
            // 且当前 root 域有权把它打上去。此前只看工具退出码，而 ksud 对错误语法同样返回 0，
            // 导致 Magisk 侧彻底失效却一直没有任何告警。
            appendLine(": > $probePath 2>/dev/null")
            appendLine("chcon u:object_r:$CONFIG_SELINUX_TYPE:s0 $probePath 2>/dev/null")
            appendLine("echo LABEL_CHECK:\$(ls -Z $probePath 2>/dev/null)")
            appendLine("rm -f $probePath 2>/dev/null")
        }
        val scriptPath = "/data/local/tmp/.lsp_sepolicy_apply.sh"
        val runCommand = """
            cat > $scriptPath
            chmod 700 $scriptPath 2>/dev/null || true
            sh $scriptPath
            rm -f $scriptPath 2>/dev/null || true
        """.trimIndent()
        val output = executeCommandWithInput(runCommand, script)

        val typeOk = Regex("TYPE_EXIT:(\\d+)").find(output)?.groupValues?.get(1) == "0"
        val allowResults = allowRules.indices.map { index ->
            Regex("ALLOW_${index}_EXIT:(\\d+)").find(output)?.groupValues?.get(1) == "0"
        }
        val allowOkCount = allowResults.count { it }
        val labelLine = Regex("LABEL_CHECK:(.*)").find(output)?.groupValues?.get(1)?.trim()
        val labelApplied = labelLine?.contains(CONFIG_SELINUX_TYPE) == true

        if (!typeOk) {
            android.util.Log.w(TAG, "sepolicy type 规则应用失败（工具: $tool），输出: $output")
        }
        if (allowOkCount < allowRules.size) {
            val failedDomains = SEPOLICY_READ_DOMAINS.filterIndexed { i, _ -> !allowResults[i] }
            android.util.Log.w(TAG, "以下域的 sepolicy 授权失败（该域名可能在本机不存在）: $failedDomains")
        }

        // 标签探针跑通了就以它为准（最可信）；探针本身没跑起来（比如 ls -Z 不可用）才退回看退出码。
        val verified = if (labelLine.isNullOrBlank()) {
            typeOk && allowOkCount > 0
        } else {
            labelApplied && allowOkCount > 0
        }

        if (verified) {
            android.util.Log.i(
                TAG,
                "sepolicy 规则已通过 $tool 应用并验证（${allowOkCount}/${allowRules.size} 个域授权成功，标签校验: ${labelLine ?: "跳过"}）"
            )
        } else {
            android.util.Log.w(
                TAG,
                "sepolicy 规则未能生效（工具: $tool，标签校验: ${labelLine ?: "未执行"}），" +
                        "目标应用大概率读不到配置文件、模拟会失效。完整输出: $output"
            )
        }
        verified
    }

    private fun toolAvailable(probeCommand: String): Boolean {
        val result = executeCommand("$probeCommand >/dev/null 2>&1; echo EXIT:\$?")
        return result.trim().endsWith("EXIT:0")
    }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.suseoaa.locationspoofer android:mock_location allow")
        result != "ERROR"
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.suseoaa.locationspoofer android:mock_location default")
        result != "ERROR"
    }

    fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.ifEmpty { "SUCCESS" }
        } catch (e: Exception) {
            "ERROR"
        }
    }

    fun executeCommandWithInput(command: String, input: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(input)
                writer.flush()
            }
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.ifEmpty { "SUCCESS" }
        } catch (e: Exception) {
            "ERROR"
        }
    }
}
