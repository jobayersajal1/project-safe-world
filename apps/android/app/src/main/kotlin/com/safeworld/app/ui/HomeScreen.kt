package com.safeworld.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.app.vpn.SafeWorldVpnService
import com.safeworld.core.Categories
import com.safeworld.core.Matcher

/**
 * Status, how much is covered, what else can be added, and the two pieces of
 * friction (PIN + uninstall protection).
 *
 * The scam, gambling, and adult lists have no switches — installing this app is
 * the decision to block them, and a toggle that turns gambling back on in a weak
 * moment is the exact failure the PIN exists to prevent. They are stated as a
 * number instead. What *is* offered is the other direction: opt-in categories
 * and the user's own domains, because adding to what's blocked never needs
 * protecting from the user.
 *
 * That asymmetry decides every PIN gate on this screen — strengthening is free,
 * weakening costs the PIN.
 */
@Composable
fun HomeScreen(
    store: SettingsStore,
    onProtectionChange: (Boolean) -> Unit,
    requestPin: RequestPin,
    onEnableUninstallProtection: () -> Unit,
    onDisableUninstallProtection: () -> Unit,
    uninstallProtectionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val stats by store.stats.collectAsStateWithLifecycle()
    val remoteDomains by store.remoteDomains.collectAsStateWithLifecycle()
    val running by SafeWorldVpnService.running.collectAsStateWithLifecycle()
    val interrupted by store.protectionInterrupted.collectAsStateWithLifecycle()

    // Recomputed when a category is toggled or an update lands, which is
    // exactly when the headline should move.
    val blockedCount = remember(settings, remoteDomains) { store.blockedDomainCount(settings) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        if (interrupted) {
            val title = stringResource(R.string.interrupted_title)
            val message = stringResource(R.string.interrupted_pin_message)
            InterruptionBanner(
                onAcknowledge = { requestPin(title, message, store::clearProtectionInterrupted) },
            )
        }

        ProtectionCard(running = running, requestPin = requestPin, onChange = onProtectionChange)

        BlockedCountCard(count = blockedCount)

        Text(stringResource(R.string.block_more_title), style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                for (category in Categories.optional) {
                    val label = labelResFor(category.id)?.let { stringResource(it) }
                        ?: category.label
                    val description = descriptionResFor(category.id)?.let { stringResource(it) }
                        ?: category.description
                    val offTitle = stringResource(R.string.category_off_pin_title, label)
                    val offMessage = stringResource(R.string.category_off_pin_message)

                    ToggleRow(
                        label = label,
                        description = description,
                        checked = settings.categories[category.id] == true,
                        onCheckedChange = { on ->
                            val apply = {
                                store.update {
                                    it.copy(categories = it.categories + (category.id to on))
                                }
                            }
                            if (on) apply() else requestPin(offTitle, offMessage, apply)
                        },
                    )
                    HorizontalDivider()
                }

                AddWebsitesSection(store = store, requestPin = requestPin)
            }
        }

        Text(stringResource(R.string.uninstall_title), style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val offTitle = stringResource(R.string.uninstall_off_pin_title)
                val offMessage = stringResource(R.string.uninstall_off_pin_message)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.uninstall_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = uninstallProtectionActive,
                        onCheckedChange = { on ->
                            if (on) {
                                onEnableUninstallProtection()
                            } else {
                                requestPin(offTitle, offMessage, onDisableUninstallProtection)
                            }
                        },
                    )
                }
                Text(
                    stringResource(R.string.uninstall_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(stringResource(R.string.blocked_today), style = MaterialTheme.typography.titleSmall)
        Card {
            Text(
                CountFormat.grouped(stats.blocked),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ProtectionCard(
    running: Boolean,
    requestPin: RequestPin,
    onChange: (Boolean) -> Unit,
) {
    val offTitle = stringResource(R.string.protection_off_pin_title)
    val offMessage = stringResource(R.string.protection_off_pin_message)

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.protection_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Reflects whether the tunnel is actually up, not the stored
                // preference — see the note in MainActivity.
                Switch(
                    checked = running,
                    onCheckedChange = { on ->
                        // Turning protection *on* is free; only weakening it
                        // costs the PIN. Friction belongs on the path out.
                        if (on) {
                            onChange(true)
                        } else {
                            requestPin(offTitle, offMessage) { onChange(false) }
                        }
                    },
                )
            }
            Text(
                stringResource(if (running) R.string.protection_on else R.string.protection_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The headline claim. Counts only the lists that are actually enabled, so it
 * rises when the user opts into more and never overstates what is happening.
 */
@Composable
private fun BlockedCountCard(count: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.blocked_headline, CountFormat.compact(count)),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.blocked_headline_detail, CountFormat.grouped(count)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The user's own block list, as a toggle that opens a comma-separated field.
 *
 * Saving is free when the new list keeps everything the old one had and costs
 * the PIN when it drops anything — including switching the whole section off,
 * which clears the list rather than leaving entries that look blocked but
 * aren't.
 */
@Composable
private fun AddWebsitesSection(store: SettingsStore, requestPin: RequestPin) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val saved = settings.customBlock

    var expanded by remember(saved.isEmpty()) { mutableStateOf(saved.isNotEmpty()) }
    // Edited as free text and only parsed on save, so a half-typed domain never
    // reaches the blocklist mid-keystroke.
    var text by remember(saved) { mutableStateOf(saved.joinToString(", ")) }

    val removeTitle = stringResource(R.string.add_websites_remove_pin_title)
    val removeMessage = stringResource(R.string.add_websites_remove_pin_message)

    Column {
        ToggleRow(
            label = stringResource(R.string.add_websites_label),
            description = if (saved.isEmpty()) {
                stringResource(R.string.add_websites_description)
            } else {
                pluralStringResource(R.plurals.add_websites_count, saved.size, saved.size)
            },
            checked = expanded,
            onCheckedChange = { on ->
                if (on) {
                    expanded = true
                } else if (saved.isEmpty()) {
                    expanded = false
                } else {
                    requestPin(removeTitle, removeMessage) {
                        store.update { it.copy(customBlock = emptyList()) }
                        expanded = false
                    }
                }
            },
        )

        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.add_websites_hint)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                )
                Text(
                    stringResource(R.string.add_websites_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val next = parseDomainList(text)
                        val dropped = saved.map(Matcher::normalizeHost).toSet() - next.toSet()
                        val apply = { store.update { it.copy(customBlock = next) } }
                        if (dropped.isEmpty()) apply() else requestPin(removeTitle, removeMessage, apply)
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.add_websites_save))
                }
            }
        }
    }
}

/**
 * Shown when the tunnel went down without the user turning it off in-app.
 * Costs the PIN to dismiss so it can't be swiped away on reflex, leaving the
 * user believing they're still covered.
 */
@Composable
private fun InterruptionBanner(onAcknowledge: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.interrupted_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.interrupted_body),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.interrupted_dismiss))
            }
        }
    }
}
