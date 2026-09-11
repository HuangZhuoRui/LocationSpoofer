package com.suseoaa.locationspoofer.data.model

data class RootSetupTestResult(
    val hasRoot: Boolean,
    val idOutput: String,
    val solution: RootSolution,
    val toolUsed: String?,
    val typeRuleOk: Boolean,
    val allowRuleResults: List<Pair<String, Boolean>>,
    val labelCheckRaw: String?,
    val labelVerified: Boolean,
    /** 唯一真正决定成败的验证：App 自己的进程（和目标 App 同一类域）实测能否读到探针文件 */
    val appCanReadProbe: Boolean,
    val configFileChconResults: List<Pair<String, Boolean>>,
    val rawScriptOutput: String,
    val overallVerified: Boolean
)
