package com.vincenthzr.locationspoofer.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.HookStatusReport
import com.vincenthzr.locationspoofer.data.model.HookStatusReport.State
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

private val StateRed = Color(0xFFE5534B)
private val StateGray = Color(0xFF8B949E)

/** 本次开机的时间点，早于它写出的报告说明本次开机模块没有在该进程里生效 */
internal fun bootTimeMs(): Long = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

/** 一级页面上的一句话概括，例如"8/9 个组件已挂载"；没有任何有效报告时返回 null */
internal fun hookStatusSummary(reports: Map<String, HookStatusReport?>): Pair<Int, Int>? {
    val fresh = reports.values.filterNotNull().filterNot { it.isStale(bootTimeMs()) }
    if (fresh.isEmpty()) return null
    return fresh.sumOf { it.hookedCount } to fresh.sumOf { it.components.size }
}

/** 系统适配页的二级页面：各系统进程的 Hook 运行状态 */
@Composable
fun HookStatusScreen(
    reports: Map<String, HookStatusReport?>,
    loading: Boolean,
    onRefresh: () -> Unit,
    debugDump: Boolean,
    onDebugDumpChange: (Boolean) -> Unit,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val boot = remember(reports) { bootTimeMs() }
    val vendorReport = reports.values.firstOrNull { it != null && !it.isStale(boot) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            SettingsPageHeader(
                title = stringResource(R.string.hook_status_title),
                subtitle = vendorReport?.let {
                    stringResource(
                        if (it.manualVendor) R.string.hook_status_adapter_manual else R.string.hook_status_adapter_auto,
                        it.vendorId
                    )
                },
                isDark = isDark,
                onBack = onClose
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentBlue)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.progress_refresh),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                reports.forEach { (process, report) -> ProcessCard(process, report, boot) }

                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.hook_status_debug_dump),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.hook_status_debug_dump_desc),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = debugDump,
                            onCheckedChange = onDebugDumpChange,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentBlue)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ProcessCard(process: String, report: HookStatusReport?, bootTime: Long) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp)
    ) {
        Text(
            stringResource(processName(process)),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        when {
            report == null -> Hint(stringResource(R.string.hook_status_no_report))
            report.isStale(bootTime) -> Hint(stringResource(R.string.hook_status_stale))
            else -> {
                Hint(
                    stringResource(
                        R.string.hook_status_updated_at,
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(report.updatedAt))
                    )
                )
                Hint(
                    if (report.configPath.isBlank()) stringResource(R.string.hook_status_config_none)
                    else stringResource(
                        R.string.hook_status_config,
                        report.configPath,
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(report.configModified))
                    )
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report.components.forEach { ComponentRow(it) }
                }
                if (report.errors.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.hook_status_errors),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = StateRed
                    )
                    report.errors.forEach {
                        Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = StateRed.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(component: HookStatusReport.Component) {
    val (color, label) = when (component.state) {
        State.HOOKED -> AccentGreen to R.string.hook_state_hooked
        State.NO_METHODS -> AccentOrange to R.string.hook_state_no_methods
        State.NOT_FOUND -> StateRed to R.string.hook_state_not_found
        State.PENDING -> StateGray to R.string.hook_state_pending
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    componentName(component.name)?.let { stringResource(it) } ?: component.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
            }
            if (component.found) {
                Text(
                    component.className,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    stringResource(R.string.hook_status_source, component.source),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                val (hooked, absent) = component.methods.entries.partition { it.value > 0 }
                if (hooked.isNotEmpty()) {
                    Text(
                        hooked.joinToString("  ") { (name, count) -> "$name ×$count" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (absent.isNotEmpty()) {
                    // 全部 ×0 时说明类找对了但方法都不在，要重点看；部分 ×0 多为兼容其他 Android 版本的备用方法名
                    Text(
                        stringResource(R.string.hook_status_absent_methods, absent.joinToString(", ") { it.key }),
                        fontSize = 11.sp,
                        color = if (hooked.isEmpty()) AccentOrange else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
}

@StringRes
private fun processName(process: String): Int = when (process) {
    "SYSTEM_SERVER" -> R.string.hook_process_system_server
    "PHONE" -> R.string.hook_process_phone
    "BLUETOOTH" -> R.string.hook_process_bluetooth
    else -> R.string.hook_process_unknown
}

/** xposed 模块 SystemComponent 的显示名；新增组件时在这里和字符串资源里补上，没补时直接显示枚举名 */
@StringRes
private fun componentName(name: String): Int? = when (name) {
    "LOCATION_MANAGER_SERVICE" -> R.string.hook_component_location_manager_service
    "LOCATION_PROVIDER_MANAGER" -> R.string.hook_component_location_provider_manager
    "WIFI_SERVICE" -> R.string.hook_component_wifi_service
    "WIFI_SCANNER_SERVICE" -> R.string.hook_component_wifi_scanner_service
    "CONNECTIVITY_SERVICE" -> R.string.hook_component_connectivity_service
    "TELEPHONY_REGISTRY" -> R.string.hook_component_telephony_registry
    "APPOPS_SERVICE" -> R.string.hook_component_appops_service
    "TELEPHONY_PHONE_MANAGER" -> R.string.hook_component_telephony_phone_manager
    "BLUETOOTH_SCAN_SERVICE" -> R.string.hook_component_bluetooth_scan_service
    else -> null
}
