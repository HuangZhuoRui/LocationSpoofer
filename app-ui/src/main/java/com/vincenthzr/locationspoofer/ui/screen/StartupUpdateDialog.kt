package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vincenthzr.locationspoofer.data.model.GithubRelease
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun StartupUpdateDialog(
    latestRelease: GithubRelease,
    onDismiss: () -> Unit,
    onNavigateToUpdate: () -> Unit,
    onIgnore: (String) -> Unit
) {
    val cleanVersion = remember(latestRelease.versionName) {
        latestRelease.versionName.trim().removePrefix("v").removePrefix("V")
    }

    Dialog(onDismissRequest = onDismiss) {
        MiuixCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            insideMargin = PaddingValues(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 顶部标题与红点徽章
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935))
                        )
                        Text(
                            text = stringResource(R.string.new_version_found),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 更新只会下载与当前安装一致的方案变体
                        com.vincenthzr.locationspoofer.ui.screen.settings.SchemeBadge()
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentBlue.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "v$cleanVersion",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlue
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                // 更新日志简报
                if (latestRelease.body.isNotBlank()) {
                    Box(modifier = Modifier.heightIn(max = 260.dp)) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item {
                                RenderMarkdownContent(markdown = latestRelease.body)
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.new_version_prompt_desc),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                // 底部操作区（左侧忽略此版本，右侧以后再说与前往更新）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ignore_this_version),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.noRippleClickable {
                            onIgnore(latestRelease.versionName)
                            onDismiss()
                        }
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                stringResource(R.string.later),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Button(
                            onClick = {
                                onDismiss()
                                onNavigateToUpdate()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.go_to_update), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
