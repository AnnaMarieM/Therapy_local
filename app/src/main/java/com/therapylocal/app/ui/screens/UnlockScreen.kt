package com.therapylocal.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.ui.security.authenticateWithBiometric
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg
import com.therapylocal.app.vault.BiometricAvailability
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSWORD = 8
private const val RESET_NOTICE = "Biometric unlock was reset because the device's biometrics changed. Unlock with your password and turn it on again in Settings."

/** First-run password setup, then password / biometric unlock. Calls [onDone] once the vault is open. */
@Composable
fun VaultGate(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(if (Vault.isSetUp(ctx)) "unlock" else "setup") }
    if (step == "biometric" && !Vault.isUnlocked) step = "unlock"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(SageLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "MindVault Lock",
                    tint = SageDark,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "MindVault",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = CharcoalText,
                fontFamily = FontFamily.Serif
            )

            Text(
                text = "Your private clinical memory",
                fontSize = 13.sp,
                color = MutedText,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            PillBadge(
                text = "100% Offline · Zero Cloud",
                icon = Icons.Default.Shield
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (step) {
                        "setup" -> SetupStep(ctx, scope, onCreated = {
                            if (Vault.biometricAvailability(ctx) == BiometricAvailability.Unsupported) onDone() else step = "biometric"
                        })
                        "biometric" -> BiometricStep(ctx, activity, scope, onDone)
                        else -> UnlockStep(ctx, activity, scope, onDone, onErased = { step = "setup" })
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, onCreated: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val longEnough = password.length >= MIN_PASSWORD
    val matches = password == confirm
    val valid = longEnough && matches && confirm.isNotEmpty()

    StepTitle("Create a password")
    StepText("You'll use it to open MindVault. It can't be recovered if you forget it.")
    Spacer(modifier = Modifier.height(12.dp))
    PasswordField(password, { password = it; error = null }, "Password", visible, { visible = !visible }, enabled = !busy, imeAction = ImeAction.Next)
    Text(
        text = "At least $MIN_PASSWORD characters",
        fontSize = 11.sp,
        color = if (password.isEmpty() || longEnough) MutedText else DangerRed,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 4.dp)
    )
    Spacer(modifier = Modifier.height(8.dp))
    PasswordField(confirm, { confirm = it; error = null }, "Confirm password", visible, { visible = !visible }, enabled = !busy)
    if (confirm.isNotEmpty() && !matches) {
        Text(
            text = "Passwords do not match",
            fontSize = 11.sp,
            color = DangerRed,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 4.dp)
        )
    }
    error?.let { ErrorText(it) }
    Spacer(modifier = Modifier.height(16.dp))
    PrimaryButton("Continue", enabled = valid, busy = busy, busyText = "Setting up…") {
        busy = true
        val chars = password.toCharArray()
        scope.launch {
            try {
                withContext(Dispatchers.Default) { Vault.create(ctx, chars) }
                onCreated()
            } catch (e: Exception) {
                error = e.message ?: "Setup failed. Please try again."
                busy = false
            } finally {
                chars.fill('\u0000')
            }
        }
    }
}

@Composable
private fun BiometricStep(ctx: android.content.Context, activity: androidx.fragment.app.FragmentActivity?, scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
    val availability = remember { Vault.biometricAvailability(ctx) }
    var error by remember { mutableStateOf<String?>(null) }

    if (availability == BiometricAvailability.Available) {
        StepTitle("Unlock faster with fingerprint or face")
        StepText("You can always use your password instead.")
        error?.let { ErrorText(it) }
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton("Enable Biometric Unlock", enabled = activity != null, icon = true) {
            error = null
            try {
                authenticateWithBiometric(
                    activity ?: return@PrimaryButton, "Enable Biometric Unlock", "Confirm with your fingerprint or face", "Not now", Vault.biometricEnrollCipher(),
                    onSuccess = { cipher ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(ctx, cipher) }
                                onDone()
                            } catch (e: Exception) {
                                error = e.message ?: "Biometric unlock could not be turned on."
                            }
                        }
                    },
                    onError = { if (it.isNotEmpty()) error = it },
                )
            } catch (e: Exception) {
                error = e.message ?: "Biometric unlock could not be turned on."
            }
        }
        TextButton(onClick = onDone) { Text("Not now", fontSize = 13.sp, color = MutedText) }
    } else {
        StepTitle("Biometric unlock")
        StepText("No fingerprint or face is enrolled on this device. Add one in Android Settings, then turn on Biometric Quick Unlock in MindVault Settings.")
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton("Continue", onClick = onDone)
    }
}

@Composable
private fun UnlockStep(
    ctx: android.content.Context,
    activity: androidx.fragment.app.FragmentActivity?,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
    onErased: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showReset by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    val biometric = remember { Vault.isBiometricEnabled(ctx) && Vault.biometricAvailability(ctx) == BiometricAvailability.Available }

    val unlockWithPassword = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            error = null
            val chars = password.toCharArray()
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.Default) { Vault.unlock(ctx, chars) }
                    if (ok) onDone() else error = "Incorrect password"
                } catch (e: Exception) {
                    error = e.message ?: "Could not unlock. Please try again."
                } finally {
                    chars.fill('\u0000')
                    busy = false
                }
            }
        }
    }

    val unlockWithBiometric = {
        if (activity != null && !busy) {
            error = null
            try {
                val cipher = Vault.biometricUnlockCipher(ctx)
                if (cipher == null) {
                    error = RESET_NOTICE
                } else {
                    authenticateWithBiometric(
                        activity, "Unlock MindVault", "Confirm with your fingerprint or face", "Use Password", cipher,
                        onSuccess = { c ->
                            busy = true
                            scope.launch {
                                try {
                                    val ok = withContext(Dispatchers.Default) { Vault.completeBiometricUnlock(ctx, c) }
                                    if (ok) onDone() else error = "Biometric unlock failed. Use your password."
                                } catch (e: Exception) {
                                    error = e.message ?: "Biometric unlock failed. Use your password."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onError = { if (it.isNotEmpty()) error = it },
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: "Biometric unlock failed. Use your password."
            }
        }
    }

    LaunchedEffect(Unit) { if (biometric) unlockWithBiometric() }

    Text(
        text = "Password",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = CharcoalText,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
    PasswordField(password, { password = it; error = null }, null, visible, { visible = !visible }, enabled = !busy, imeAction = ImeAction.Done, onDone = unlockWithPassword)
    error?.let { ErrorText(it) }
    Spacer(modifier = Modifier.height(16.dp))
    PrimaryButton("Unlock", enabled = password.isNotEmpty(), busy = busy, busyText = "Unlocking…") { unlockWithPassword() }

    if (biometric) {
        TextButton(onClick = { unlockWithBiometric() }, enabled = !busy) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = SageDark, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text("Unlock with Biometrics", fontSize = 13.sp, color = SageDark)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (!showReset) {
        TextButton(onClick = { showReset = true }) {
            Text("Forgot password?", fontSize = 12.sp, color = MutedText)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Your password can't be recovered. You can erase MindVault and start over — all patient data on this device will be permanently deleted.",
                fontSize = 11.sp,
                color = MutedText,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = { confirmErase = true }) { Text("Erase and Start Over…", fontSize = 12.sp, color = DangerRed) }
            TextButton(onClick = { showReset = false }) { Text("Cancel", fontSize = 11.sp, color = SageDark) }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase everything?") },
            text = { Text("All patient data on this device will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Vault.erase(ctx) }
                            password = ""
                            error = null
                            onErased()
                        } catch (e: Exception) {
                            error = e.message ?: "Could not erase. Please try again."
                        }
                    }
                }) { Text("Erase Everything", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = CharcoalText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StepText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MutedText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = DangerRed,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    visible: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = label?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle password",
                    tint = MutedText
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SageGreen,
            unfocusedBorderColor = BorderSubtle
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String = "",
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SageGreen,
            contentColor = Color.White,
            disabledContainerColor = if (busy) SageGreen else BorderSubtle,
            disabledContentColor = if (busy) Color.White else MutedText,
        )
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(modifier = Modifier.size(10.dp))
            Text(text = busyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            if (icon) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
