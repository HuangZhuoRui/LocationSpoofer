package com.vincenthzr.locationspoofer.ui.screen.managedata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable

@Composable
fun ManageDeleteConfirmDialog(
    title: String = stringResource(R.string.delete),
    message: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(
                                    alpha = 0.05f
                                )
                            )
                            .noRippleClickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = stringResource(R.string.cancel), fontSize = 13.5.sp)
                    }

                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(40.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
