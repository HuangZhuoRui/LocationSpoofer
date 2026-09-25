package com.vincenthzr.locationspoofer.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.ui.components.ModernSettingsInput
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.setAmapApiKey
import com.vincenthzr.locationspoofer.viewmodel.setBaiduApiKey
import com.vincenthzr.locationspoofer.viewmodel.setGoogleApiKey
import com.vincenthzr.locationspoofer.viewmodel.setMapEngine
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun MapEngineSettingsScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var localAmapApiKey by remember(uiState.amapApiKey) { mutableStateOf(uiState.amapApiKey) }
    var localBaiduApiKey by remember(uiState.baiduApiKey) { mutableStateOf(uiState.baiduApiKey) }
    var localGoogleApiKey by remember(uiState.googleApiKey) { mutableStateOf(uiState.googleApiKey) }

    BackHandler(onBack = onClose)

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF22272E) else Color.White)
                        .border(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(
                                0xFFE5E8EC
                            ),
                            shape = CircleShape
                        )
                        .noRippleClickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = if (isDark) Color.White else Color(0xFF1A1D20),
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(R.string.map_config),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.map_engine_and_keys),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(AccentGreen.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Map,
                                    null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.map_config),
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.map_engine_and_keys),
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 地图引擎 4 分段选择器
                        Text(
                            text = stringResource(R.string.current_engine),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val engines = listOf(
                                MapEngine.AUTO to stringResource(R.string.engine_auto_short),
                                MapEngine.AMAP to stringResource(R.string.engine_amap_short),
                                MapEngine.BAIDU to stringResource(R.string.engine_baidu_short),
                                MapEngine.GOOGLE to stringResource(R.string.engine_google_short)
                            )
                            engines.forEach { (engine, label) ->
                                val isSelected = uiState.mapEngine == engine
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) AccentBlue
                                            else if (isDark) Color.White.copy(alpha = 0.05f)
                                            else Color.Black.copy(alpha = 0.04f)
                                        )
                                        .noRippleClickable { viewModel.setMapEngine(engine) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        // 各引擎自定义 API Key 输入
                        AnimatedVisibility(
                            visible = uiState.mapEngine == MapEngine.AMAP || uiState.mapEngine == MapEngine.BAIDU || uiState.mapEngine == MapEngine.GOOGLE,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(modifier = Modifier.padding(top = 14.dp)) {
                                when (uiState.mapEngine) {
                                    MapEngine.AMAP -> {
                                        ModernSettingsInput(
                                            label = stringResource(R.string.custom_amap_key),
                                            value = localAmapApiKey,
                                            onValueChange = { localAmapApiKey = it },
                                            placeholder = stringResource(R.string.custom_amap_key_hint),
                                            isDark = isDark
                                        )
                                    }

                                    MapEngine.BAIDU -> {
                                        ModernSettingsInput(
                                            label = stringResource(R.string.custom_baidu_key),
                                            value = localBaiduApiKey,
                                            onValueChange = { localBaiduApiKey = it },
                                            placeholder = stringResource(R.string.custom_baidu_key_hint),
                                            isDark = isDark
                                        )
                                    }

                                    MapEngine.GOOGLE -> {
                                        ModernSettingsInput(
                                            label = stringResource(R.string.custom_google_key),
                                            value = localGoogleApiKey,
                                            onValueChange = { localGoogleApiKey = it },
                                            placeholder = stringResource(R.string.custom_google_key_hint),
                                            isDark = isDark
                                        )
                                    }

                                    else -> Unit
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.setAmapApiKey(localAmapApiKey)
                            viewModel.setBaiduApiKey(localBaiduApiKey)
                            viewModel.setGoogleApiKey(localGoogleApiKey)
                            Toast.makeText(
                                context,
                                context.getString(R.string.restart_required_hint),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text(
                            stringResource(R.string.save),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    AppColors.background(isDark),
                                    AppColors.background(isDark).copy(alpha = 0.85f),
                                    AppColors.background(isDark).copy(alpha = 0.40f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}
