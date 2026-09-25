package com.vincenthzr.locationspoofer.ui.screen

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.selectLanguage
import androidx.compose.ui.platform.LocalLocale

data class LanguageOption(val name: String, val code: String, val nativeName: String)

val LANGUAGES = listOf(
    LanguageOption("English", "en", "English"),
    LanguageOption("Chinese", "zh", "简体中文"),
    LanguageOption("Arabic", "ar", "العربية")
)

@Composable
fun LanguageSelectionScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val systemLocale = LocalLocale.current.platformLocale.language

    // 如果支持，则默认为系统语言，否则使用 English
    val defaultLang = when {
        systemLocale.startsWith("zh") -> "zh"
        systemLocale.startsWith("ar") -> "ar"
        else -> "en"
    }

    var selectedLang by remember { mutableStateOf(defaultLang) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Language,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(40.dp)
                )
            }

            Text(
                text = "Select Language / 选择语言 / اختر اللغة",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LANGUAGES.forEach { lang ->
                    LanguageItem(
                        option = lang,
                        isSelected = selectedLang == lang.code,
                        onClick = { selectedLang = lang.code }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.selectLanguage(selectedLang)
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            selectedLang
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text(
                    text = when (selectedLang) {
                        "zh" -> "确定"
                        "ar" -> "موافق"
                        else -> "Confirm"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun LanguageItem(
    option: LanguageOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) AccentBlue.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            2.dp,
            AccentBlue
        ) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.nativeName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = option.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            if (isSelected) {
                RadioButton(
                    selected = true,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = AccentBlue)
                )
            }
        }
    }
}
