package com.safeworld.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.safeworld.app.R
import com.safeworld.app.security.PinAttempt
import com.safeworld.app.security.RecoveryCode
import kotlinx.coroutines.delay

/** Shortest PIN we accept. Four digits is the familiar phone-unlock length. */
const val MIN_PIN_LENGTH = 4

private fun String.digitsOnly() = filter { it.isDigit() }

/**
 * A challenge the user must pass before a protection-weakening action goes
 * through. [onVerified] runs only on a correct PIN.
 */
data class PinChallenge(
    val title: String,
    val message: String,
    val onVerified: () -> Unit,
)

/**
 * Asks for the existing PIN. Deliberately gives no hint about how wrong the
 * entry was, and stays open until the user passes or explicitly cancels.
 */
@Composable
fun PinPromptDialog(
    challenge: PinChallenge,
    verify: (String) -> PinAttempt,
    lockedMillis: Long,
    onForgot: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalContext.current.resources
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // Ticks once a second so the cooldown visibly counts down instead of
    // looking frozen.
    var remaining by remember(lockedMillis) { mutableStateOf(lockedMillis) }

    LaunchedEffect(lockedMillis) {
        remaining = lockedMillis
        while (remaining > 0) {
            delay(1000)
            remaining = (remaining - 1000).coerceAtLeast(0)
        }
    }

    val locked = remaining > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (locked) stringResource(R.string.pin_locked_title) else challenge.title)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (locked) {
                        stringResource(R.string.pin_locked_body, formatCountdown(remaining))
                    } else {
                        challenge.message
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!locked) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.digitsOnly(); message = null },
                        label = { Text(stringResource(R.string.pin_field)) },
                        singleLine = true,
                        isError = message != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Offered while locked out as well — that is the state people
                // are usually in when they reach for it.
                TextButton(onClick = onForgot) { Text(stringResource(R.string.pin_forgot)) }
            }
        },
        confirmButton = {
            if (!locked) {
                TextButton(
                    enabled = pin.length >= MIN_PIN_LENGTH,
                    onClick = {
                        when (val result = verify(pin)) {
                            is PinAttempt.Success -> {
                                challenge.onVerified()
                                onDismiss()
                            }
                            is PinAttempt.Wrong -> {
                                // Resolved from `resources` rather than
                                // `pluralStringResource`, which can only be
                                // called during composition, not from a click.
                                message = resources.getQuantityString(
                                    R.plurals.pin_incorrect,
                                    result.attemptsRemaining,
                                    result.attemptsRemaining,
                                )
                                pin = ""
                            }
                            is PinAttempt.LockedOut -> {
                                remaining = result.millisRemaining
                                message = null
                                pin = ""
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (locked) R.string.action_close else R.string.action_cancel))
            }
        },
    )
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Choosing a PIN — first run, changing it, or after a recovery reset. The three
 * differ only in wording, so they share one screen rather than drifting apart.
 */
@Composable
fun SetPinScreen(
    onPinChosen: (String) -> Unit,
    title: String = stringResource(R.string.pin_choose_title),
    confirmLabel: String = stringResource(R.string.pin_set),
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = pin.length < MIN_PIN_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != pin
    val canSubmit = !tooShort && confirm == pin

    Column(
        modifier = modifier
            .fillMaxSize()
            // Shown outside the Scaffold, so it gets no inset padding of its
            // own and the title would otherwise sit under the status bar.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.pin_choose_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.digitsOnly() },
            label = { Text(stringResource(R.string.pin_field_min, MIN_PIN_LENGTH)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it.digitsOnly() },
            label = { Text(stringResource(R.string.pin_confirm_field)) },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        if (mismatch) {
            Text(
                stringResource(R.string.pin_mismatch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = { onPinChosen(pin) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(confirmLabel) }

        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * Shows a freshly issued recovery code. This is the only moment it is readable
 * — only its hash is stored — so the screen can't be left until the user says
 * they have written it down.
 */
@Composable
fun RecoveryCodeScreen(
    code: String,
    isReplacement: Boolean,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.recovery_show_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(
                if (isReplacement) R.string.recovery_replaced else R.string.recovery_show_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(16.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
            ) { Text(stringResource(R.string.recovery_copy)) }
            if (copied) {
                Text(
                    stringResource(R.string.recovery_copied),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_acknowledge))
        }
    }
}

/**
 * The forgotten-PIN path: prove you hold the recovery code, then pick a new
 * PIN. Two steps, so a wrong code is rejected immediately rather than after the
 * user has invented and confirmed a PIN they won't get to keep.
 */
@Composable
fun RecoverPinScreen(
    hasRecoveryCode: Boolean,
    verifyCode: (String) -> Boolean,
    onReset: (code: String, newPin: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }

    if (accepted) {
        SetPinScreen(
            onPinChosen = { onReset(code, it) },
            title = stringResource(R.string.recovery_new_pin_title),
            confirmLabel = stringResource(R.string.pin_save),
            onCancel = onCancel,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.recovery_enter_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(
                if (hasRecoveryCode) R.string.recovery_enter_body else R.string.recovery_none,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (hasRecoveryCode) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; wrong = false },
                label = { Text(stringResource(R.string.recovery_field)) },
                isError = wrong,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) {
                Text(
                    stringResource(R.string.recovery_wrong),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { if (verifyCode(code)) accepted = true else wrong = true },
                // PBKDF2 costs ~100ms, so don't spend it on input that can't be
                // a code in the first place.
                enabled = RecoveryCode.isWellFormed(code),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_continue)) }
        }

        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
