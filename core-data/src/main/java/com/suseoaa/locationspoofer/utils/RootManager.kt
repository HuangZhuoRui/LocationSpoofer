package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RootSetupTestResult
import com.suseoaa.locationspoofer.data.model.RootSolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class RootManager {

    /** ensureSepolicyRules()/testRootSetup() 共享的内部诊断结果，不含 root 检测本身的信息 */
    private data class SepolicySetupDetail(
        val toolUsed: String?,
        val typeRuleOk: Boolean,
        val allowRuleResults: List<Pair<String, Boolean>>,
        val labelCheckRaw: String?,
        val labelVerified: Boolean,
        val appCanReadProbe: Boolean,
        val configFileChconResults: List<Pair<String, Boolean>>,
        val rawScriptOutput: String,
        val overallVerified: Boolean
    )

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

        private val CONFIG_FILE_PATHS = listOf(
            "/data/local/tmp/locationspoofer_config.json",
            "/data/system/locationspoofer_config.json",
            "/data/data/com.suseoaa.locationspoofer/files/locationspoofer_config.json"
        )

        /**
         * 按 root 方案分组的 live sepolicy patch 候选命令：同一方案内按顺序探测、谁能用就用谁，
         * 不同方案之间不再互相尝试——用户显式选择方案后，只在该方案已知的候选范围内找工具，
         * 取代旧的"一份大杂烩表、谁能用就用谁"的跨方案兜底探测。
         *
         * Magisk 与 APatch：APatch 官方文档确认它内部直接复用 Magisk 的 magiskpolicy 二进制
         * （不是独立实现），只是运行时释放到 /data/adb/ap/bin/magiskpolicy 这个固定路径，
         * 所以两者候选表结构相同、只是默认路径优先级不同。
         *
         * KernelSU / SukiSU Ultra / ReSukiSU Ultra：后两者都是 KernelSU 的下游 fork（ReSukiSU
         * 是 SukiSU Ultra 的再下游），没有查到任何官方文档证据表明它们的 ksud 二进制路径或
         * sepolicy 命令语法与上游 KernelSU 有差异，/data/adb/ksu/bin 这个目录约定在整个生态里
         * 反复被确认为标准位置。这三个方案在这里复用同一份候选表——这不是"没做区分"，而是它们
         * 本就该收敛到同一条实现；UI 上把它们分成三个选项，是为了让用户能明确表达自己用的是哪个
         * 分支，方便以后一旦发现某个 fork 真的有例外情况时单独加候选，而不必退回大杂烩探测。
         */
        private val MAGISK_CANDIDATES = listOf(
            "magiskpolicy --live" to "command -v magiskpolicy",
            "/data/adb/magisk/magiskpolicy --live" to "[ -x /data/adb/magisk/magiskpolicy ]"
        )
        private val APATCH_CANDIDATES = listOf(
            "/data/adb/ap/bin/magiskpolicy --live" to "[ -x /data/adb/ap/bin/magiskpolicy ]",
            "magiskpolicy --live" to "command -v magiskpolicy"
        )
        private val KERNELSU_FAMILY_CANDIDATES = listOf(
            "ksud sepolicy patch" to "command -v ksud",
            "/data/adb/ksu/bin/ksud sepolicy patch" to "[ -x /data/adb/ksu/bin/ksud ]"
        )
        private val TOOL_CANDIDATES: Map<RootSolution, List<Pair<String, String>>> = mapOf(
            RootSolution.MAGISK to MAGISK_CANDIDATES,
            RootSolution.APATCH to APATCH_CANDIDATES,
            RootSolution.KERNELSU to KERNELSU_FAMILY_CANDIDATES,
            RootSolution.SUKISU_ULTRA to KERNELSU_FAMILY_CANDIDATES,
            RootSolution.RESUKISU_ULTRA to KERNELSU_FAMILY_CANDIDATES,
            // AUTO：没有明确方案时的兜底，沿用改造前的全集探测行为，外加 supolicy 这个老式工具
            RootSolution.AUTO to (MAGISK_CANDIDATES + APATCH_CANDIDATES + KERNELSU_FAMILY_CANDIDATES +
                listOf("supolicy --live" to "command -v supolicy")).distinct()
        )
    }

    suspend fun checkRootAccess(solution: RootSolution = RootSolution.AUTO): Boolean =
        withContext(Dispatchers.IO) {
            val hasRoot = executeCommand("id").contains("uid=0(root)")
            if (hasRoot) {
                applyRootBackgroundExemptions()
                ensureSepolicyRules(solution)
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

    suspend fun ensureSepolicyRules(solution: RootSolution = RootSolution.AUTO): Boolean =
        withContext(Dispatchers.IO) {
            runSepolicySetup(solution).overallVerified
        }

    /**
     * 检测 root 权限与 sepolicy 规则注入是否正常，返回完整的分步骤诊断结果，
     * 供设置页"测试"按钮展示——即使规则应用失败，用户也能在 App 里直接看到具体卡在哪一步，
     * 不需要再依赖容易被系统冲刷掉的 logcat。
     */
    suspend fun testRootSetup(solution: RootSolution = RootSolution.AUTO): RootSetupTestResult =
        withContext(Dispatchers.IO) {
            val idOutput = executeCommand("id")
            val hasRoot = idOutput.contains("uid=0(root)")
            val detail = if (hasRoot) {
                runSepolicySetup(solution)
            } else {
                SepolicySetupDetail(
                    toolUsed = null,
                    typeRuleOk = false,
                    allowRuleResults = SEPOLICY_READ_DOMAINS.map { it to false },
                    labelCheckRaw = null,
                    labelVerified = false,
                    appCanReadProbe = false,
                    configFileChconResults = CONFIG_FILE_PATHS.map { it to false },
                    rawScriptOutput = "",
                    overallVerified = false
                )
            }
            RootSetupTestResult(
                hasRoot = hasRoot,
                idOutput = idOutput,
                solution = solution,
                toolUsed = detail.toolUsed,
                typeRuleOk = detail.typeRuleOk,
                allowRuleResults = detail.allowRuleResults,
                labelCheckRaw = detail.labelCheckRaw,
                labelVerified = detail.labelVerified,
                appCanReadProbe = detail.appCanReadProbe,
                configFileChconResults = detail.configFileChconResults,
                rawScriptOutput = detail.rawScriptOutput,
                overallVerified = detail.overallVerified
            )
        }

    /**
     * 为跨进程配置文件动态打入 live SELinux 策略：新建专属 type，只放行需要读它的域。
     * 取代旧的"chmod 666 + 蹭 shell_data_file/system_data_file 通用类型"方案。
     * 探测不到 solution 对应候选范围内的任何工具时记录日志、不做跨方案兜底降级
     * （AUTO 除外，它本身就是"全候选合并"，充当没有明确方案时的兜底）。
     *
     * 规则不能作为内联 CLI 参数拼进 `su -c "..."` 字符串下发：实测在 KernelSU 上，
     * 带空格/花括号的引号参数经过 su -c 转发后会被拆成多个参数，导致 ksud/magiskpolicy
     * 把规则解析错(报 "unexpected argument")。改为把规则写成脚本文件、用 sh 执行该文件，
     * 规则内容不再经过任何命令行参数层，从根上避免这类跨 shell 转发丢引号的问题。
     */
    private fun runSepolicySetup(solution: RootSolution): SepolicySetupDetail {
        val candidates = TOOL_CANDIDATES[solution] ?: TOOL_CANDIDATES.getValue(RootSolution.AUTO)
        val tool = candidates.firstOrNull { (_, probe) -> toolAvailable(probe) }?.first
        if (tool == null) {
            android.util.Log.w(
                TAG,
                "方案 $solution 下未找到可用的 sepolicy 工具（候选: ${candidates.map { it.first }}），配置文件可能无法被目标应用读取"
            )
            return SepolicySetupDetail(
                toolUsed = null,
                typeRuleOk = false,
                allowRuleResults = SEPOLICY_READ_DOMAINS.map { it to false },
                labelCheckRaw = null,
                labelVerified = false,
                appCanReadProbe = false,
                configFileChconResults = CONFIG_FILE_PATHS.map { it to false },
                rawScriptOutput = "",
                overallVerified = false
            )
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
            // 保险：确保目录本身对非 root 进程可进入(x)，不依赖 applyRootBackgroundExemptions()
            // 已经被调用过——testRootSetup() 可能在那之前就被触发。
            appendLine("chmod 755 /data/local/tmp 2>/dev/null || true")
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
            // 注意：这一步验证的是 su/Magisk 自己这个近乎不受 SELinux 限制的强势域能否
            // 读写该文件，不代表 untrusted_app_all/gmscore_app 这些真正的目标域也被放行了
            // ——这里先 chmod 644 确保 DAC 不是拦截因素，故意不在这一步删除探针文件，
            // 留给下面 App 自己进程（而不是 su）做一次真实的跨域读取验证。
            appendLine(": > $probePath 2>/dev/null")
            appendLine("chmod 644 $probePath 2>/dev/null")
            appendLine("chcon u:object_r:$CONFIG_SELINUX_TYPE:s0 $probePath 2>/dev/null")
            appendLine("echo LABEL_CHECK:\$(ls -Z $probePath 2>/dev/null)")
            // 防御性保险：重启后这个 type 曾一度在内核里不存在（见开机日志里的
            // "is not valid (left unmapped)"），磁盘上残留的旧配置文件此时读不到。
            // 规则重新打上后，理论上旧文件的 xattr 标签会自动被重新解析为有效，
            // 但个别 ROM 在开机流程里可能对这几个路径跑过 restorecon 把标签冲掉，
            // 这里顺手在同一次 su 里重新 chcon 一遍，不需要额外开进程，成本接近零。
            CONFIG_FILE_PATHS.forEachIndexed { index, configPath ->
                appendLine(
                    "if [ -f $configPath ]; then chcon u:object_r:$CONFIG_SELINUX_TYPE:s0 $configPath 2>/dev/null; " +
                        "echo CHCON_${index}_EXIT:\$?; else echo CHCON_${index}_EXIT:MISSING; fi"
                )
            }
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
        val allowRuleResults = SEPOLICY_READ_DOMAINS.mapIndexed { index, domain -> domain to allowResults[index] }
        val labelLine = Regex("LABEL_CHECK:(.*)").find(output)?.groupValues?.get(1)?.trim()
        val labelApplied = labelLine?.contains(CONFIG_SELINUX_TYPE) == true
        val configFileChconResults = CONFIG_FILE_PATHS.mapIndexed { index, path ->
            path to (Regex("CHCON_${index}_EXIT:(\\d+)").find(output)?.groupValues?.get(1) == "0")
        }

        // 真实的跨域验证：App 自己的进程（运行在 untrusted_app 系的域下，和真正要读这份
        // 配置文件的微信/GMS 等目标 App 是同一类域）直接尝试读探针文件，不再借道 su。
        // su/Magisk 的域几乎不受这条自定义规则约束，上面的 LABEL_CHECK 只能证明"su 自己
        // 能操作这个文件"，不能证明目标域真的被放行——这一步才是真正决定"目标 App 能不能
        // 读到配置"的验证，读不到就直接判定整体未生效，不管前面几步的工具退出码多干净。
        val appCanReadProbe = try {
            // 探针文件本来就是空文件，读到空字符串也算成功；关键是这个操作本身
            // 有没有抛出异常（MAC 拒绝时会是 FileNotFoundException: ... EACCES）。
            File(probePath).readText()
            true
        } catch (e: Exception) {
            false
        }
        executeCommand("rm -f $probePath 2>/dev/null")

        if (!typeOk) {
            android.util.Log.w(TAG, "sepolicy type 规则应用失败（方案: $solution，工具: $tool），输出: $output")
        }
        if (allowOkCount < allowRules.size) {
            val failedDomains = SEPOLICY_READ_DOMAINS.filterIndexed { i, _ -> !allowResults[i] }
            android.util.Log.w(TAG, "以下域的 sepolicy 授权失败（该域名可能在本机不存在）: $failedDomains")
        }

        // 最终判定必须以 appCanReadProbe 为准：这是唯一真正验证了"目标域能不能读到文件"
        // 的一步，su 自己的标签探针（labelApplied）只是前置的辅助诊断信息，不能替代它——
        // 这正是这次改动要修的坑：此前只看 su 自证的结果，出现过"测试全部通过、但微信/
        // 支付宝实际读取仍 EACCES"的假阳性。
        val verified = appCanReadProbe && allowOkCount > 0

        if (verified) {
            android.util.Log.i(
                TAG,
                "sepolicy 规则已通过 $tool 应用并验证（方案: $solution，${allowOkCount}/${allowRules.size} 个域授权成功，标签校验: ${labelLine ?: "跳过"}，App 自身实测可读: $appCanReadProbe）"
            )
        } else {
            android.util.Log.w(
                TAG,
                "sepolicy 规则未能生效（方案: $solution，工具: $tool，标签校验: ${labelLine ?: "未执行"}，App 自身实测可读: $appCanReadProbe），" +
                    "目标应用大概率读不到配置文件、模拟会失效。完整输出: $output"
            )
        }

        return SepolicySetupDetail(
            toolUsed = tool,
            typeRuleOk = typeOk,
            allowRuleResults = allowRuleResults,
            labelCheckRaw = labelLine,
            labelVerified = labelApplied,
            appCanReadProbe = appCanReadProbe,
            configFileChconResults = configFileChconResults,
            rawScriptOutput = output,
            overallVerified = verified
        )
    }

    private fun toolAvailable(probeCommand: String): Boolean {
        val result = executeCommand("$probeCommand >/dev/null 2>&1; echo EXIT:\$?")
        return result.trim().endsWith("EXIT:0")
    }

    /**
     * 强制停止目标 App，逼迫它们下次启动时重新走一次全新的 SELinux 访问判定，
     * 不再受历史 AVC 缓存（进程在规则重新打上之前就已建立的"拒绝"判定不会自动刷新）
     * 或者其他同样操作 sepolicy 的模块的影响——这是"规则检测通过但目标 App 仍读不到
     * 配置"这类问题最直接有效的解法。
     */
    suspend fun forceStopApps(packages: List<String>): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            packages.map { pkg -> pkg to (executeCommand("am force-stop $pkg") != "ERROR") }
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
