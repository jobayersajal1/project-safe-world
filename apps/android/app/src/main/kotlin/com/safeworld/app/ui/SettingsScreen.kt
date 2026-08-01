package com.safeworld.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeworld.app.LocaleHelper
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.app.update.UpdateManager
import com.safeworld.app.update.UpdateState

/**
 * Language, PIN management, the allow list, and a link to the store.
 *
 * The block list lives on the home screen, not here — it is the same
 * `customBlock` either way, and two editors for one list is how they end up
 * disagreeing. What stays here is the allow list, the one edit that can
 * *unblock* something, which is why the whole save is gated.
 */
@Composable
fun SettingsScreen(
    store: SettingsStore,
    requestPin: RequestPin,
    onChangePin: () -> Unit,
    onShowNewRecoveryCode: () -> Unit,
    updateManager: UpdateManager,
    installedVersion: String,
    modifier: Modifier = Modifier,
) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val language by store.language.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var allowText by remember(settings.customAllow) {
        mutableStateOf(settings.customAllow.joinToString("\n"))
    }

    val savePinTitle = stringResource(R.string.settings_save_pin_title)
    val savePinMessage = stringResource(R.string.settings_save_pin_message)
    val changePinTitle = stringResource(R.string.pin_change_title)
    val changePinMessage = stringResource(R.string.pin_change_pin_message)
    val recoveryTitle = stringResource(R.string.recovery_settings_label)
    val recoveryMessage = stringResource(R.string.recovery_settings_pin_message)
    val noStore = stringResource(R.string.settings_rate_unavailable)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                LocaleHelper.SUPPORTED.forEachIndexed { index, tag ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { store.setLanguage(tag) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Null handler: the row is the tap target, and a button
                        // with its own would swallow taps that landed on it.
                        RadioButton(selected = tag == language, onClick = null)
                        Text(
                            LocaleHelper.displayName(tag, context),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }

        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall)
        Card {
            val darkPreference by store.darkTheme.collectAsStateWithLifecycle()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_dark_theme),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_theme_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = darkPreference,
                    onCheckedChange = { store.setDarkTheme(it) },
                )
            }
        }

        Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.pin_change),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.pin_change_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { requestPin(changePinTitle, changePinMessage, onChangePin) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.pin_change))
                    }
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.recovery_settings_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.recovery_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            requestPin(recoveryTitle, recoveryMessage, onShowNewRecoveryCode)
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.recovery_settings_action))
                    }
                }
            }
        }

        Text(
            stringResource(R.string.settings_allow_title),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = allowText,
            onValueChange = { allowText = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        )
        Text(
            stringResource(R.string.settings_allow_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                // Any edit here can unblock something, so the whole save is
                // gated rather than trying to work out which lines are new.
                requestPin(savePinTitle, savePinMessage) {
                    store.update { it.copy(customAllow = parseDomainList(allowText)) }
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.settings_save))
        }

        Text(
            stringResource(R.string.settings_rate_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_rate_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        if (!openStoreListing(context)) {
                            Toast.makeText(context, noStore, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.settings_rate_action))
                }
            }
        }

        UpdateSection(updateManager, installedVersion)

        HelpSection()
    }
}

/**
 * Installed version, and whatever the update check has found.
 *
 * Not PIN-gated: updating can only strengthen protection, and the PIN exists to
 * make weakening deliberate. Gating it would mean a user who forgot their PIN
 * also stops receiving fixes.
 */
@Composable
private fun UpdateSection(manager: UpdateManager, installedVersion: String) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Throttled inside the manager, so returning to this tab doesn't re-ask.
    LaunchedEffect(Unit) { manager.check() }

    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.update_installed, installedVersion),
                style = MaterialTheme.typography.bodyLarge,
            )

            when (val s = state) {
                is UpdateState.Idle -> UpdateAction(
                    label = stringResource(R.string.update_check_action),
                    onClick = { manager.check(force = true) },
                )

                is UpdateState.Checking -> Text(
                    stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is UpdateState.UpToDate -> {
                    Text(
                        stringResource(R.string.update_up_to_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UpdateAction(
                        label = stringResource(R.string.update_check_action),
                        onClick = { manager.check(force = true) },
                    )
                }

                is UpdateState.Available -> {
                    val size = s.update.sizeBytes
                    Text(
                        if (size > 0) {
                            stringResource(
                                R.string.update_available_sized,
                                s.update.version,
                                Formatter.formatShortFileSize(context, size),
                            )
                        } else {
                            stringResource(R.string.update_available, s.update.version)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // Android won't let us install until this is granted, so
                    // say so up front rather than after a download.
                    if (s.update.apkUrl != null && !manager.canInstallPackages()) {
                        Text(
                            stringResource(R.string.update_permission_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UpdateAction(
                            label = stringResource(R.string.update_permission_action),
                            onClick = { manager.requestInstallPermission() },
                        )
                    } else {
                        UpdateAction(
                            label = if (s.update.apkUrl != null) {
                                stringResource(R.string.update_download_action)
                            } else {
                                // Release exists but carries no APK — the web
                                // page is still somewhere to go.
                                stringResource(R.string.update_view_release_action)
                            },
                            onClick = { manager.download(s.update) },
                        )
                    }
                }

                is UpdateState.Downloading -> {
                    Text(
                        stringResource(R.string.update_downloading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Indeterminate until the size is known, rather than a bar
                    // frozen at zero.
                    if (s.fraction != null) {
                        LinearProgressIndicator(
                            progress = { s.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is UpdateState.Installing -> Text(
                    stringResource(R.string.update_installing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is UpdateState.Failed -> {
                    Text(
                        stringResource(s.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    UpdateAction(
                        label = stringResource(R.string.update_check_action),
                        onClick = { manager.check(force = true) },
                    )
                }
            }
        }
    }
}

/** ColumnScope so it can sit at the end of the card, matching the other actions here. */
@Composable
private fun ColumnScope.UpdateAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) {
        Text(label)
    }
}

/** Contact routes, for when the app itself is the thing in the way. */
@Composable
private fun HelpSection() {
    val context = LocalContext.current
    val noApp = stringResource(R.string.help_no_app)

    fun open(uri: String) {
        if (!openUri(context, uri)) {
            Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
        }
    }

    Text(stringResource(R.string.settings_help), style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.help_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            ContactRow(
                label = stringResource(R.string.help_whatsapp),
                value = SupportLinks.WHATSAPP_NUMBER,
                onClick = { open(SupportLinks.whatsappUrl) },
            )
            HorizontalDivider()

            ContactRow(
                label = stringResource(R.string.help_email),
                value = SupportLinks.EMAIL,
                onClick = { open(SupportLinks.emailUrl) },
            )
            HorizontalDivider()

            // No link yet, so the row shows why rather than being a dead tap.
            val group = SupportLinks.FACEBOOK_GROUP
            ContactRow(
                label = stringResource(R.string.help_facebook),
                value = group ?: stringResource(R.string.help_facebook_unavailable),
                onClick = group?.let { { open(it) } },
            )
            HorizontalDivider()

            ContactRow(
                label = stringResource(R.string.help_github),
                value = SupportLinks.GITHUB,
                onClick = { open(SupportLinks.GITHUB) },
            )
        }
    }

    LicenceSection(open = ::open)
}

/**
 * The licence notice, which is an obligation rather than a courtesy.
 *
 * Safe World for Android is GPL-3.0, because its packet forwarder is NetGuard's. GPL-3.0
 * requires the licence and the copyright to reach the person running the software — a file
 * in the repository is not that, since almost nobody who installs an APK ever sees it.
 */
@Composable
private fun LicenceSection(open: (String) -> Unit) {
    Text(stringResource(R.string.licence_title), style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.licence_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            ContactRow(
                label = stringResource(R.string.licence_gpl),
                value = SupportLinks.GPL_URL,
                onClick = { open(SupportLinks.GPL_URL) },
            )
            HorizontalDivider()

            ContactRow(
                label = stringResource(R.string.licence_netguard),
                value = SupportLinks.NETGUARD_URL,
                onClick = { open(SupportLinks.NETGUARD_URL) },
            )
        }
    }
}

/** A contact line. A null [onClick] renders it as text rather than a tap target. */
@Composable
private fun ContactRow(label: String, value: String, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Returns false when nothing installed can handle [uri]. */
private fun openUri(context: Context, uri: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Opens this app's store listing, falling back to the Play website when no
 * store app is installed — the normal case on a de-Googled device or an
 * emulator rather than an error worth surfacing.
 *
 * Deliberately not Play's in-app review API: that pulls in Play Services, does
 * nothing at all when the quota is spent or the app was sideloaded, and this
 * app has to work on devices with no Play at all.
 *
 * Returns false only when neither a store nor a browser can handle it.
 */
private fun openStoreListing(context: Context): Boolean {
    val targets = listOf(
        "market://details?id=${context.packageName}",
        "https://play.google.com/store/apps/details?id=${context.packageName}",
    )
    for (uri in targets) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Fall through to the next target.
        }
    }
    return false
}
