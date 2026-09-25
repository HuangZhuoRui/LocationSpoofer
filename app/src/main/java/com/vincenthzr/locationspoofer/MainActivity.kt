package com.vincenthzr.locationspoofer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.screen.BlockingScreen
import com.vincenthzr.locationspoofer.ui.screen.InitializingScreen
import com.vincenthzr.locationspoofer.ui.screen.LanguageSelectionScreen
import com.vincenthzr.locationspoofer.ui.theme.AppColorSchemeDark
import com.vincenthzr.locationspoofer.ui.theme.AppColorSchemeLight
import com.vincenthzr.locationspoofer.utils.LocaleUtils
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.getSavedLanguage
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModel()

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isLangSet = prefs.getBoolean("is_language_set", false)
        val lang = if (isLangSet) prefs.getString("language", "") ?: "" else ""

        val context = if (lang.isNotEmpty()) {
            LocaleUtils.wrap(newBase, lang)
        } else {
            newBase
        }
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 仅在初始启动或语言变更后同步系统 Locale
        // AppCompatDelegate.setApplicationLocales 会自动处理持久化
        val savedLang = viewModel.getSavedLanguage()
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (savedLang.isNotEmpty() && (currentLocales.isEmpty || currentLocales.toLanguageTags() != savedLang)) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(savedLang)
            )
        }

        // 语言切换、旋转等导致的重建不重复跳转授权页，只在冷启动时检查一次
        overlayPromptHandled = savedInstanceState != null
        checkAndRequestPermissions()

        setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                val uiState by viewModel.uiState.collectAsState()
                val isDark = isSystemInDarkTheme()
                val colorScheme = if (isDark) AppColorSchemeDark else AppColorSchemeLight
                val baseDensity = LocalDensity.current
                val appDensity = remember(baseDensity.density, baseDensity.fontScale) {
                    Density(baseDensity.density, baseDensity.fontScale.coerceAtMost(1.15f))
                }

                // 核心：在 Compose 层级内部通过 CompositionLocalProvider 动态刷新
                // 使用 remember(uiState.currentLanguage) 确保语言切换时重新计算 Context
                val context = LocalContext.current
                val wrappedContext = remember(uiState.currentLanguage) {
                    if (uiState.currentLanguage.isNotEmpty()) {
                        LocaleUtils.wrap(context, uiState.currentLanguage)
                    } else {
                        context
                    }
                }
                val configuration = wrappedContext.resources.configuration

                CompositionLocalProvider(
                    LocalContext provides wrappedContext,
                    LocalConfiguration provides configuration,
                    LocalDensity provides appDensity
                ) {
                    MaterialTheme(colorScheme = colorScheme) {
                        top.yukonga.miuix.kmp.theme.MiuixTheme(
                            colors = if (isDark) {
                                top.yukonga.miuix.kmp.theme.darkColorScheme()
                            } else {
                                top.yukonga.miuix.kmp.theme.lightColorScheme()
                            }
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                MainScreen(
                                    viewModel = viewModel,
                                    uiState = uiState,
                                    isDark = isDark
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissions(notGranted.toTypedArray(), 100)
        } else {
            checkBackgroundLocation()
            requestOverlayPermissionIfNeeded()
            requestIgnoreBatteryOptimizations()
        }
    }

    private var overlayPromptHandled = false

    /** 悬浮摇杆需要"显示在其他应用上层"权限：启动时检查，没有就直接跳到系统授权页 */
    private fun requestOverlayPermissionIfNeeded() {
        if (overlayPromptHandled || android.provider.Settings.canDrawOverlays(this)) return
        overlayPromptHandled = true
        android.widget.Toast.makeText(
            this,
            getString(com.vincenthzr.locationspoofer.ui.R.string.overlay_permission_on_launch),
            android.widget.Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) checkBackgroundLocation()
            // 悬浮窗权限与运行时权限无关，被拒绝时也照样检查
            requestOverlayPermissionIfNeeded()
            if (granted) requestIgnoreBatteryOptimizations()
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    uiState: com.vincenthzr.locationspoofer.data.model.AppState,
    isDark: Boolean
) {
    when {
        uiState.isInitializing -> InitializingScreen(isDark)
        !uiState.isLanguageSet -> LanguageSelectionScreen(viewModel)
        !uiState.hasRootAccess -> com.vincenthzr.locationspoofer.ui.screen.DataCollectionAssistScreen(
            viewModel = viewModel,
            uiState = uiState,
            isDark = isDark
        )
        !uiState.isLSPosedActive -> BlockingScreen(
            icon = Icons.Rounded.Extension,
            title = stringResource(R.string.lsposed_not_active),
            message = stringResource(R.string.lsposed_message),
            isDark = isDark
        )
        else -> com.vincenthzr.locationspoofer.ui.screen.MainScaffoldScreen(
            viewModel = viewModel,
            uiState = uiState
        )
    }
}
