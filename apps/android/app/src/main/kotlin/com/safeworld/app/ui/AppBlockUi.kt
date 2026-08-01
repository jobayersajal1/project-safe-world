package com.safeworld.app.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.app.vpn.BlockedApps
import com.safeworld.app.vpn.SafeWorldVpnService
import com.safeworld.core.AppGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picking individual apps by hand — the row, and the dialog behind it.
 *
 * **The three subject rows above already block apps**; this is what they can't reach. The built-in
 * catalogue only knows the apps we thought of, and the one someone actually loses hours to is often
 * not on it — a new game, something regional, something released last week.
 *
 * Blocking by app rather than by domain is the only rule an app cannot get around with
 * DNS-over-HTTPS or an address it already knows, and it costs the full tunnel: every packet on the
 * device crosses Safe World so a blocked app's traffic can be dropped by owning UID. That price is
 * stated once, by the consent dialog `HomeScreen` owns — hence [withConsent] arriving as a
 * parameter rather than being decided here.
 */
@Composable
fun ChooseAppsRow(
    store: SettingsStore,
    requestPin: RequestPin,
    withConsent: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val revision by store.appBlockRevision.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf(false) }

    // Recomputed on every selection change: the count is the only feedback that a change did
    // anything, since none of these apps are named on this screen.
    val blocked = remember(revision) {
        AppGroups.blockedPackages(
            enabledGroups = store.blockedAppGroups(),
            userPicked = store.blockedPackages(),
        )
    }
    val installed = remember(blocked) {
        BlockedApps.get(context).also { it.refresh(store) }.installedCount
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.appblock_choose_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                // "N chosen, M installed" rather than just N: most of the built-in catalogue is
                // not on any one phone, and a count that included apps the device does not have
                // would overstate what is actually being blocked.
                stringResource(
                    R.string.appblock_choose_summary,
                    CountFormat.grouped(blocked.size),
                    CountFormat.grouped(installed),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking) {
        AppPickerDialog(
            store = store,
            requestPin = requestPin,
            onConsentNeeded = withConsent,
            onDismiss = { picking = false },
        )
    }
}

private data class InstalledApp(val packageName: String, val label: String)

/**
 * Every app on the device, tickable.
 *
 * The built-in catalogue only knows the apps we thought of, and the one someone actually loses
 * hours to is often not on it — a new game, something regional, something released last week. This
 * is the answer to that, and it is why the manifest asks for `QUERY_ALL_PACKAGES`.
 *
 * Ticking is free; **unticking costs the PIN**, applied once when the dialog is saved rather than
 * per row, so the user is not asked five times to undo five apps.
 */
@Composable
private fun AppPickerDialog(
    store: SettingsStore,
    requestPin: RequestPin,
    onConsentNeeded: (() -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    val initial = remember { store.blockedPackages() }
    val selected = remember { mutableStateOf(initial) }

    // Off the main thread: `getInstalledApplications` plus a label lookup per app is hundreds of
    // binder calls, and doing it in composition drops frames on a phone with a lot installed.
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadInstalledApps(context) } }

    val removeTitle = stringResource(R.string.appblock_remove_pin_title)
    val removeMessage = stringResource(R.string.appblock_off_pin_message)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appblock_choose_label)) },
        text = {
            val loaded = apps
            if (loaded == null) {
                Text(stringResource(R.string.appblock_loading))
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(loaded, key = { it.packageName }) { app ->
                        val checked = app.packageName in selected.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected.value = if (checked) {
                                        selected.value - app.packageName
                                    } else {
                                        selected.value + app.packageName
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val next = selected.value
                val save = {
                    store.setBlockedPackages(next)
                    SafeWorldVpnService.refresh(context)
                    onDismiss()
                }
                when {
                    // Anything dropped from the list weakens protection, so it costs the PIN —
                    // the same rule the category switches and the custom block list follow.
                    !initial.all { it in next } -> requestPin(removeTitle, removeMessage, save)
                    next.size > initial.size -> onConsentNeeded(save)
                    else -> save()
                }
            }) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.appblock_consent_cancel))
            }
        },
    )
}

/**
 * Launchable apps, minus ourselves.
 *
 * Filtered to things with a launcher entry so the list is what the user recognises from their home
 * screen, rather than the couple of hundred system packages behind it. Safe World is excluded
 * because blocking our own UID would cut off the update check and the blocklist fetch — and would
 * not stop protection, so it is pure self-harm with no upside.
 */
private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .asSequence()
        .filter { it.packageName != context.packageName }
        .filter { info ->
            // Updated system apps count: YouTube ships preinstalled on most phones and is exactly
            // what someone opening this screen came to block.
            val updatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            (!system || updatedSystem) && pm.getLaunchIntentForPackage(info.packageName) != null
        }
        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}
