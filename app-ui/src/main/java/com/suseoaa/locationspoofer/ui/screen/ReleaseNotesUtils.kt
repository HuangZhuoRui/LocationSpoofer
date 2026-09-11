package com.suseoaa.locationspoofer.ui.screen

import android.content.Context
import com.suseoaa.locationspoofer.data.model.GithubRelease
import com.suseoaa.locationspoofer.ui.R

data class GroupedReleaseNotes(
    val features: List<String>,
    val fixes: List<String>,
    val others: List<String>
)

fun parseAndCategorizeReleaseNotes(releases: List<GithubRelease>): GroupedReleaseNotes {
    val features = mutableListOf<String>()
    val fixes = mutableListOf<String>()
    val others = mutableListOf<String>()

    val featureHeaderKeywords = listOf(
        "feature", "feat", "add", "new", "improve", "optimize", "enhancement",
        "功能", "新增", "特性", "新功能", "优化", "改进", "提速", "增强",
        "ميزة", "جديد", "تحسين", "ترقية", "إضافة"
    )
    val fixHeaderKeywords = listOf(
        "fix", "bug", "crash", "issue", "solve", "repair",
        "修复", "解决", "崩溃", "问题", "纠正", "故障",
        "إصلاح", "حل", "تصحيح", "عطل"
    )

    for (release in releases) {
        val lines = release.body.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var currentSection = "other"

        for (rawLine in lines) {
            val cleanLine = rawLine.trim()
            if (cleanLine.isEmpty()) continue

            if (cleanLine.startsWith("#")) {
                val headingText = cleanLine.replace(Regex("^#+\\s*"), "").lowercase()
                if (featureHeaderKeywords.any { headingText.contains(it) }) {
                    currentSection = "feature"
                } else if (fixHeaderKeywords.any { headingText.contains(it) }) {
                    currentSection = "fix"
                } else {
                    currentSection = "other"
                }
                continue
            }

            val isListItem = cleanLine.startsWith("- ") || cleanLine.startsWith("* ") ||
                    cleanLine.startsWith("+ ") || cleanLine.startsWith("• ") ||
                    cleanLine.matches(Regex("""^\d+[\.\)]\s+.*"""))

            if (isListItem) {
                val itemContent = when {
                    cleanLine.startsWith("- ") || cleanLine.startsWith("* ") ||
                            cleanLine.startsWith("+ ") || cleanLine.startsWith("• ") -> cleanLine.substring(
                        2
                    ).trim()

                    else -> cleanLine.replace(Regex("""^\d+[\.\)]\s*"""), "").trim()
                }

                if (itemContent.isEmpty()) continue

                val itemLower = itemContent.lowercase()
                var category = currentSection

                if (category == "other") {
                    if (fixHeaderKeywords.any { itemLower.contains(it) }) {
                        category = "fix"
                    } else if (featureHeaderKeywords.any { itemLower.contains(it) }) {
                        category = "feature"
                    }
                }

                when (category) {
                    "feature" -> features.add(itemContent)
                    "fix" -> fixes.add(itemContent)
                    else -> others.add(itemContent)
                }
            } else {
                if (!cleanLine.startsWith("```") && !cleanLine.startsWith(">")) {
                    others.add(cleanLine)
                }
            }
        }
    }

    return GroupedReleaseNotes(
        features = features.distinct(),
        fixes = fixes.distinct(),
        others = others.distinct()
    )
}

fun generateMergedMarkdown(
    context: Context,
    grouped: GroupedReleaseNotes
): String {
    val sb = StringBuilder()

    if (grouped.features.isNotEmpty()) {
        sb.append("## ").append(context.getString(R.string.features_header)).append("\n")
        grouped.features.forEach { item ->
            sb.append("- ").append(item).append("\n")
        }
        sb.append("\n")
    }

    if (grouped.fixes.isNotEmpty()) {
        sb.append("## ").append(context.getString(R.string.fixes_header)).append("\n")
        grouped.fixes.forEach { item ->
            sb.append("- ").append(item).append("\n")
        }
        sb.append("\n")
    }

    if (grouped.others.isNotEmpty()) {
        sb.append("## ").append(context.getString(R.string.others_header)).append("\n")
        grouped.others.forEach { item ->
            if (item.length < 120) {
                sb.append("- ").append(item).append("\n")
            } else {
                sb.append(item).append("\n\n")
            }
        }
    }

    return sb.toString().trim()
}

data class SemVer(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    val isBeta: Boolean = false,
    val betaNumber: Int = 0
) : Comparable<SemVer> {
    companion object {
        fun parse(version: String): SemVer {
            val clean = version.lowercase().removePrefix("v").trim()
            val dashIndex = clean.indexOf('-').let { if (it >= 0) it else clean.indexOf('_') }
            val coreStr = if (dashIndex >= 0) clean.substring(0, dashIndex) else clean
            val suffix = if (dashIndex >= 0) clean.substring(dashIndex + 1) else ""

            val coreParts = coreStr.split('.').map { it.toIntOrNull() ?: 0 }
            val major = coreParts.getOrElse(0) { 0 }
            val minor = coreParts.getOrElse(1) { 0 }
            val patch = coreParts.getOrElse(2) { 0 }

            val isBeta = suffix.contains("beta")
            val betaNumber = if (isBeta) {
                Regex("""\d+""").find(suffix)?.value?.toIntOrNull() ?: 1
            } else 0

            return SemVer(major, minor, patch, isBeta, betaNumber)
        }
    }

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        // 正式版优先于同一版本的 Beta 版 (例如 2.1.0 > 2.1.0-beta-1)
        if (!isBeta && other.isBeta) return 1
        if (isBeta && !other.isBeta) return -1
        if (!isBeta && !other.isBeta) return 0

        // 都是 Beta 版，比较 Beta 序号 (例如 beta-2 > beta-1)
        return betaNumber.compareTo(other.betaNumber)
    }
}

fun isNewerVersion(versionStr: String, currentStr: String): Boolean {
    return try {
        SemVer.parse(versionStr) > SemVer.parse(currentStr)
    } catch (e: Exception) {
        false
    }
}
