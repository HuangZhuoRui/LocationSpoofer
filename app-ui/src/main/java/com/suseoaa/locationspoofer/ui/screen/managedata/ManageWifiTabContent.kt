package com.suseoaa.locationspoofer.ui.screen.managedata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.locationspoofer.ui.R
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.noRippleClickable

@Composable
fun ManageWifiTabContent(
    wifiList: List<EditableWifiItem>,
    selectedWifiBssid: String?,
    isDark: Boolean,
    onSelectBssid: (String?) -> Unit,
    onAddWifi: () -> Unit,
    onEditWifi: (EditableWifiItem) -> Unit,
    onDeleteWifi: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.12f else 0.06f),
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.manage_wifi_list_title),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentBlue.copy(alpha = 0.12f))
                    .noRippleClickable(onClick = onAddWifi)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.add_wifi_btn),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.designated_wifi_tip),
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
            lineHeight = 16.sp
        )

        val isAutoSelected = selectedWifiBssid == null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isAutoSelected) AccentBlue.copy(alpha = 0.08f) else Color.Transparent)
                .border(
                    0.8.dp,
                    if (isAutoSelected) AccentBlue.copy(alpha = 0.35f) else Color.Transparent,
                    RoundedCornerShape(10.dp)
                )
                .noRippleClickable { onSelectBssid(null) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isAutoSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isAutoSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.4f
                ),
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.no_designated_wifi),
                fontSize = 13.sp,
                fontWeight = if (isAutoSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isAutoSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.8f
                )
            )
        }

        if (wifiList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_wifi_data_in_record),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                wifiList.forEach { wifi ->
                    val isSelected =
                        selectedWifiBssid?.equals(wifi.bssid, ignoreCase = true) == true

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) AccentBlue.copy(alpha = 0.09f)
                                else if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(
                                    alpha = 0.02f
                                )
                            )
                            .border(
                                0.8.dp,
                                if (isSelected) AccentBlue.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.08f else 0.04f),
                                RoundedCornerShape(12.dp)
                            )
                            .noRippleClickable { onSelectBssid(wifi.bssid) }
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.4f
                                ),
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = wifi.ssid.ifBlank { "<Hidden SSID>" },
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AccentBlue.copy(alpha = 0.15f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.designated_simulation_badge),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentBlue
                                            )
                                        }
                                    }

                                    if (wifi.isConnected) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AccentGreen.copy(alpha = 0.15f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.connected_wifi_badge),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentGreen
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(3.dp))

                                Text(
                                    text = "${wifi.bssid}  •  ${wifi.level} dBm  •  ${wifi.frequency} MHz",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )

                                if (wifi.capabilities.isNotBlank()) {
                                    Text(
                                        text = wifi.capabilities,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        maxLines = 1
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.06f
                                        )
                                    )
                                    .noRippleClickable { onEditWifi(wifi) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(13.dp)
                                )
                            }

                            Spacer(Modifier.width(6.dp))

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935).copy(alpha = 0.08f))
                                    .noRippleClickable { onDeleteWifi(wifi.bssid) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFE53935),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
