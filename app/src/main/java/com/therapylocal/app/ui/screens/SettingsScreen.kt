package com.therapylocal.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.ui.security.authenticateWithBiometric
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.vault.BiometricAvailability
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerBg
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg

@Composable
fun SettingsScreen(
    onLockVault: () -> Unit,
    onOpenDebugMlView: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    var biometricsEnabled by remember { mutableStateOf(Vault.isBiometricEnabled(context)) }
    val availability = Vault.biometricAvailability(context)
    var autoSurfacePastNotes by remember { mutableStateOf(true) }
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = CharcoalText
            )
            Text(
                text = "Security and preferences",
                fontSize = 12.sp,
                color = MutedText
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsGroupHeader(title = "SECURITY")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsToggleRow(
                            icon = Icons.Default.Fingerprint,
                            title = "Biometric Quick Unlock",
                            subtitle = when (availability) {
                                BiometricAvailability.Available -> "Fingerprint / Face Unlock"
                                BiometricAvailability.NotEnrolled -> "No fingerprint or face set up on this device"
                                BiometricAvailability.Unsupported -> "Not available on this device"
                            },
                            checked = biometricsEnabled,
                            enabled = availability == BiometricAvailability.Available || biometricsEnabled,
                            onCheckedChange = { on ->
                                if (!on) {
                                    Vault.disableBiometric(context)
                                    biometricsEnabled = false
                                } else if (activity != null) {
                                    try {
                                        authenticateWithBiometric(
                                            activity, "Turn on Biometric Unlock", "Confirm with your fingerprint or face", "Cancel", Vault.biometricEnrollCipher(),
                                            onSuccess = { cipher ->
                                                scope.launch {
                                                    withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(context, cipher) }
                                                    biometricsEnabled = true
                                                }
                                            },
                                            onError = { if (it.isNotEmpty()) Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Biometric unlock could not be turned on.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            item {
                SettingsGroupHeader(title = "SETUP")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (setup.state) {
                            SetupState.Ready -> SettingsRow(
                                icon = Icons.Default.CheckCircle,
                                title = "MindVault is ready",
                                subtitle = "Everything is set up on this phone"
                            )
                            SetupState.Failed -> {
                                SettingsRow(
                                    icon = Icons.Default.Memory,
                                    title = "Setup was interrupted",
                                    subtitle = setup.message ?: "Please check your connection and try again"
                                )
                                TextButton(onClick = { ModelSetup.retry(context) }) { Text("Try again") }
                            }
                            else -> {
                                val eta = setup.etaSeconds?.let { " · ${ModelSetup.formatEta(it)} left" } ?: ""
                                SettingsRow(
                                    icon = Icons.Default.Memory,
                                    title = "Getting ready",
                                    subtitle = if (setup.totalBytes > 0) "${ModelSetup.formatBytes(setup.doneBytes)} of ${ModelSetup.formatBytes(setup.totalBytes)}$eta" else "Checking what is needed"
                                )
                                if (setup.totalBytes > 0) LinearProgressIndicator(progress = { setup.fraction }, modifier = Modifier.fillMaxWidth(), color = SageGreen)
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SageGreen)
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroupHeader(title = "PREFERENCES")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsToggleRow(
                            icon = Icons.Default.Security,
                            title = "Show past notes",
                            subtitle = "Bring up relevant quotes from earlier sessions while you talk",
                            checked = autoSurfacePastNotes,
                            onCheckedChange = { autoSurfacePastNotes = it }
                        )
                    }
                }
            }

            if (onOpenDebugMlView != null) {
                item {
                    SettingsGroupHeader(title = "DEVELOPER")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDebugMlView() },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Developer tools",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = CharcoalText
                                )
                                Text(
                                    text = "Logs, raw chunks and diagnostics",
                                    fontSize = 12.sp,
                                    color = MutedText
                                )
                            }
                            PillBadge(text = "Developer")
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onLockVault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerBg,
                        contentColor = DangerRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Lock",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MutedText,
        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SageLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SageDark,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = CharcoalText
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MutedText
            )
        }
    }
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SageLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SageDark,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = CharcoalText
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MutedText
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SageGreen,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BorderSubtle
            )
        )
    }
}
