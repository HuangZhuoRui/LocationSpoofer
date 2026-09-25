package com.vincenthzr.locationspoofer.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vincenthzr.locationspoofer.data.model.RootSetupTestResult
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable

@Composable
private fun DiagnosticItemRow(label: String, ok: Boolean, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
            contentDescription = null,
            tint = if (ok) AccentGreen else Color(0xFFE53935),
            modifier = Modifier
                .size(16.dp)
                .padding(top = 1.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun RootSetupTestResultDialog(
    result: RootSetupTestResult,
    isDark: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val overallOk = result.hasRoot && result.overallVerified
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background((if (overallOk) AccentGreen else Color(0xFFE53935)).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (overallOk) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                            contentDescription = null,
                            tint = if (overallOk) AccentGreen else Color(0xFFE53935),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.root_test_result_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DiagnosticItemRow(
                        label = stringResource(R.string.root_test_item_root),
                        ok = result.hasRoot,
                        detail = result.idOutput.take(120)
                    )
                    DiagnosticItemRow(
                        label = stringResource(R.string.root_test_item_tool),
                        ok = result.toolUsed != null,
                        detail = result.toolUsed ?: stringResource(R.string.root_test_tool_not_found)
                    )
                    DiagnosticItemRow(
                        label = stringResource(R.string.root_test_item_type_rule),
                        ok = result.typeRuleOk
                    )
                    result.allowRuleResults.forEach { (domain, ok) ->
                        DiagnosticItemRow(
                            label = stringResource(R.string.root_test_item_allow_domain, domain),
                            ok = ok
                        )
                    }
                    DiagnosticItemRow(
                        label = stringResource(R.string.root_test_item_label_check),
                        ok = result.labelVerified,
                        detail = result.labelCheckRaw
                    )
                    DiagnosticItemRow(
                        label = stringResource(R.string.root_test_item_app_read),
                        ok = result.appCanReadProbe,
                        detail = stringResource(R.string.root_test_item_app_read_detail)
                    )
                    result.configFileChconResults.forEach { (path, ok) ->
                        DiagnosticItemRow(
                            label = path,
                            ok = ok
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(
                                    alpha = 0.05f
                                )
                            )
                            .noRippleClickable {
                                val summary = buildString {
                                    appendLine("solution=${result.solution}")
                                    appendLine("hasRoot=${result.hasRoot}")
                                    appendLine("idOutput=${result.idOutput}")
                                    appendLine("toolUsed=${result.toolUsed}")
                                    appendLine("typeRuleOk=${result.typeRuleOk}")
                                    appendLine("allowRuleResults=${result.allowRuleResults}")
                                    appendLine("labelVerified=${result.labelVerified}")
                                    appendLine("labelCheckRaw=${result.labelCheckRaw}")
                                    appendLine("configFileChconResults=${result.configFileChconResults}")
                                    appendLine("rawScriptOutput=${result.rawScriptOutput}")
                                }
                                clipboardManager.setText(AnnotatedString(summary))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.copied_to_clipboard),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.copy_diagnostic_info),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            stringResource(R.string.close),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RestartHookedAppsConfirmDialog(
    apps: List<com.vincenthzr.locationspoofer.data.model.AppInfoItem>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0xFFE53935).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.restart_hooked_apps_confirm_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (apps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.restart_hooked_apps_empty),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 20.sp
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text(stringResource(R.string.close), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = stringResource(R.string.restart_hooked_apps_confirm_message, apps.size),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 20.sp
                    )

                    Text(
                        text = apps.joinToString("、") { it.appName },
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        lineHeight = 18.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(
                                        alpha = 0.05f
                                    )
                                )
                                .noRippleClickable(onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }

                        Button(
                            onClick = onConfirm,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(42.dp)
                        ) {
                            Text(
                                stringResource(R.string.restart_hooked_apps_confirm_button),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
