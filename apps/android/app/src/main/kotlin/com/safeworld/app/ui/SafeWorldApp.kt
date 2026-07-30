package com.safeworld.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.app.SubscriptionStore
import com.safeworld.core.Subscriptions
import com.safeworld.app.security.PinLockout
import com.safeworld.app.update.UpdateManager

private enum class Tab(val labelRes: Int) {
    Home(R.string.tab_home),
    Settings(R.string.tab_settings),
}

/** Asks for the PIN, then runs the action. `(title, message, action)`. */
typealias RequestPin = (String, String, () -> Unit) -> Unit

/**
 * A screen that takes over the whole app until it is finished.
 *
 * Everything here either establishes the PIN or replaces it, so none of it can
 * sit behind the tabs — and a recovery code that is readable exactly once must
 * not be dismissible by tapping somewhere else.
 */
private sealed interface Gate {
    /** Normal operation: the tabs are showing. */
    data object None : Gate

    /** Changing the PIN, after the current one was entered. */
    data object ChangePin : Gate

    /** Forgotten PIN: enter the recovery code, then choose a new one. */
    data object Recover : Gate

    /** A newly issued code, shown once. [replacement] changes only the wording. */
    data class ShowRecoveryCode(val code: String, val replacement: Boolean) : Gate
}

/**
 * Two tabs mirroring the iOS app's `RootView`, gated by the PIN.
 *
 * The challenge state lives here rather than in each screen so there is one
 * place that decides what needs a PIN: every weakening action funnels through
 * [RequestPin], and a screen can't accidentally skip the gate.
 */
@Composable
fun SafeWorldApp(
    store: SettingsStore,
    subscriptions: SubscriptionStore,
    onProtectionChange: (Boolean) -> Unit,
    onSubscriptionsChanged: () -> Unit,
    onEnableUninstallProtection: () -> Unit,
    onDisableUninstallProtection: () -> Unit,
    uninstallProtectionActive: Boolean,
    updateManager: UpdateManager,
    installedVersion: String,
) {
    val hasPin by store.hasPin.collectAsStateWithLifecycle()
    val hasRecoveryCode by store.hasRecoveryCode.collectAsStateWithLifecycle()
    val subscriptionsAccepted by subscriptions.accepted.collectAsStateWithLifecycle()
    // Skipping hides the offer for this launch only; `remember` does not survive
    // process death, so the next time the app is opened it asks again. That is
    // the requested behaviour: one dismissal is not a permanent answer, but it is
    // never asked twice in the same sitting either.
    var offerSkippedThisSession by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var challenge by remember { mutableStateOf<PinChallenge?>(null) }
    var gate by remember { mutableStateOf<Gate>(Gate.None) }

    // A PIN with no way back is one forgotten number away from "clear app
    // data", which also discards every setting and the uninstall friction. So
    // as soon as a PIN exists, issue a code and make the user save it.
    //
    // In an effect rather than inline: issuing writes to storage, and doing
    // that during composition would mint a fresh code on every recomposition.
    LaunchedEffect(hasPin, hasRecoveryCode) {
        if (hasPin && !hasRecoveryCode) {
            gate = Gate.ShowRecoveryCode(store.issueRecoveryCode(), replacement = false)
        }
    }

    // No PIN yet means first run: set one before anything can be configured, so
    // protection is never established with nothing guarding it.
    if (!hasPin) {
        SetPinScreen(onPinChosen = store::setPin)
        return
    }

    // After the PIN and the recovery code, and only once. These feeds belong to
    // the two categories with no off switch, so once one is on, turning it off
    // costs the PIN — asking before the PIN existed would leave a window where the
    // strongest protection in the app could be undone with one tap.
    if (hasPin && hasRecoveryCode && gate is Gate.None && !subscriptionsAccepted && !offerSkippedThisSession) {
        SubscriptionOfferScreen(
            onAccept = {
                subscriptions.acceptAll()
                // Downloading is left to the scheduled worker rather than done
                // here: several megabytes must not stand between someone and a
                // working app, and a failed fetch must not strand them in setup.
                onSubscriptionsChanged()
            },
            onSkip = { offerSkippedThisSession = true },
        )
        return
    }

    when (val current = gate) {
        is Gate.ChangePin -> {
            SetPinScreen(
                onPinChosen = { store.setPin(it); gate = Gate.None },
                title = stringResource(R.string.pin_change_title),
                confirmLabel = stringResource(R.string.pin_save),
                onCancel = { gate = Gate.None },
            )
            return
        }

        is Gate.Recover -> {
            RecoverPinScreen(
                hasRecoveryCode = hasRecoveryCode,
                verifyCode = store::verifyRecoveryCode,
                onReset = { code, newPin ->
                    gate = if (store.resetPinWithRecoveryCode(code, newPin)) {
                        // That code is spent now, so hand out a replacement
                        // instead of leaving them with none.
                        Gate.ShowRecoveryCode(store.issueRecoveryCode(), replacement = true)
                    } else {
                        Gate.None
                    }
                },
                onCancel = { gate = Gate.None },
            )
            return
        }

        is Gate.ShowRecoveryCode -> {
            RecoveryCodeScreen(
                code = current.code,
                isReplacement = current.replacement,
                onAcknowledge = { gate = Gate.None },
            )
            return
        }

        is Gate.None -> Unit
    }

    challenge?.let { pending ->
        val lockout by store.pinLockout.collectAsStateWithLifecycle()
        PinPromptDialog(
            challenge = pending,
            verify = store::verifyPin,
            lockedMillis = PinLockout.remaining(lockout, System.currentTimeMillis()),
            onForgot = {
                // Drops the pending action on purpose: recovering the PIN
                // shouldn't also carry out whatever was being authorized when
                // they got stuck.
                challenge = null
                gate = Gate.Recover
            },
            onDismiss = { challenge = null },
        )
    }

    val requestPin: RequestPin = { title, message, action ->
        challenge = PinChallenge(title, message, action)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (entry in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            val icon = when (entry) {
                                Tab.Home -> Icons.Filled.Shield
                                Tab.Settings -> Icons.Filled.Settings
                            }
                            Icon(icon, contentDescription = null)
                        },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Home -> HomeScreen(
                store = store,
                subscriptions = subscriptions,
                onProtectionChange = onProtectionChange,
                requestPin = requestPin,
                onEnableUninstallProtection = onEnableUninstallProtection,
                onDisableUninstallProtection = onDisableUninstallProtection,
                uninstallProtectionActive = uninstallProtectionActive,
                modifier = Modifier.padding(padding),
            )
            Tab.Settings -> SettingsScreen(
                store = store,
                requestPin = requestPin,
                onChangePin = { gate = Gate.ChangePin },
                onShowNewRecoveryCode = {
                    gate = Gate.ShowRecoveryCode(store.issueRecoveryCode(), replacement = false)
                },
                updateManager = updateManager,
                installedVersion = installedVersion,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
