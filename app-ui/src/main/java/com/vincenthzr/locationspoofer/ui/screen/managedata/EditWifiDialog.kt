package com.vincenthzr.locationspoofer.ui.screen.managedata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import java.util.Locale

@Composable
fun EditWifiDialog(
    initialItem: EditableWifiItem?,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        bssid: String,
        ssid: String,
        frequency: Int,
        level: Int,
        capabilities: String,
        vendor: String,
        isConnected: Boolean,
        isDesignated: Boolean
    ) -> Unit
) {
    val isEdit = initialItem != null
    var ssid by remember { mutableStateOf(initialItem?.ssid ?: "") }
    var bssid by remember { mutableStateOf(initialItem?.bssid ?: "") }
    var level by remember { mutableIntStateOf(initialItem?.level ?: -55) }
    var frequency by remember { mutableIntStateOf(initialItem?.frequency ?: 5180) }
    var capabilities by remember { mutableStateOf(initialItem?.capabilities ?: "[WPA2-PSK-CCMP][RSN]") }
    var vendor by remember { mutableStateOf(initialItem?.vendor ?: "") }
    var isConnected by remember { mutableStateOf(initialItem?.isConnected ?: false) }
    var isDesignated by remember { mutableStateOf(initialItem?.isDesignated ?: true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // 顶部标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(AccentBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(if (isEdit) R.string.edit_wifi_title else R.string.new_wifi_title),
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                            .noRippleClickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 表单主体
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // SSID 输入
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wifi_ssid_label),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicTextField(
                            value = ssid,
                            onValueChange = { ssid = it },
                            textStyle = TextStyle(
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (ssid.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.wifi_ssid_hint),
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    )
                                }
                                inner()
                            }
                        )
                    }

                    // BSSID (MAC) 输入
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wifi_bssid_label),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicTextField(
                            value = bssid,
                            onValueChange = { bssid = it.uppercase() },
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (bssid.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.wifi_bssid_hint),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    )
                                }
                                inner()
                            }
                        )
                    }

                    // 信号强度 RSSI
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wifi_level_label, level),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))

                        // 快速选择强度按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(-40 to "-40 极强", -55 to "-55 良好", -70 to "-70 一般", -85 to "-85 微弱").forEach { (lvl, lbl) ->
                                val isChosen = level == lvl
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isChosen) AccentBlue else if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                                        .noRippleClickable { level = lvl }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lbl,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isChosen) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }
                    }

                    // 工作频率 (Frequency)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wifi_freq_label),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(2412 to "2.4G (2412)", 5180 to "5G (5180)", 5745 to "5.8G (5745)").forEach { (freq, lbl) ->
                                val isChosen = frequency == freq
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isChosen) AccentBlue else if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                                        .noRippleClickable { frequency = freq }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lbl,
                                        fontSize = 11.sp,
                                        fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isChosen) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }
                    }

                    // 加密属性 (Capabilities)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wifi_capabilities_label),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicTextField(
                            value = capabilities,
                            onValueChange = { capabilities = it },
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("[WPA2-PSK-CCMP][RSN]" to "WPA2", "[WPA3-SAE]" to "WPA3", "[ESS]" to "OPEN").forEach { (cap, tag) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (capabilities == cap) AccentBlue.copy(alpha = 0.15f) else if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                                        .noRippleClickable { capabilities = cap }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (capabilities == cap) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // 开关选项容器
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        // 设为当前已连接 Wi-Fi 开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.wifi_is_connected_label),
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = isConnected,
                                onCheckedChange = { isConnected = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentGreen
                                )
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.08f else 0.04f))

                        // 设为模拟首选 Wi-Fi 开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.designated_simulation_badge),
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = isDesignated,
                                onCheckedChange = { isDesignated = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentBlue
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 底部操作按钮
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
                            .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                            .noRippleClickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = stringResource(R.string.cancel), fontSize = 13.5.sp)
                    }

                    Button(
                        onClick = {
                            val finalBssid = bssid.ifBlank {
                                String.format(Locale.US, "02:00:00:%02X:%02X:%02X", (0..255).random(), (0..255).random(), (0..255).random())
                            }
                            val finalSsid = ssid.ifBlank { "Mock_WiFi" }
                            onSave(
                                finalBssid,
                                finalSsid,
                                frequency,
                                level,
                                capabilities,
                                vendor,
                                isConnected,
                                isDesignated
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(42.dp)
                    ) {
                        Text(text = stringResource(R.string.save_wifi), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
