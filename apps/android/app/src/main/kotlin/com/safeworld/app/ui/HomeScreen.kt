package com.safeworld.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeworld.app.R
import com.safeworld.app.blur.BlurConsentActivity
import com.safeworld.app.blur.BlurService
import com.safeworld.app.SettingsStore
import com.safeworld.app.SubscriptionStore
import com.safeworld.app.vpn.SafeWorldVpnService
import com.safeworld.core.AppGroups.AppGroup
import com.safeworld.core.Categories
import com.safeworld.core.BlurTarget
import com.safeworld.core.CategoryId
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
    subscriptions: SubscriptionStore,
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

    // `blocklists` is a plain field read once per DNS query, so Compose has
    // nothing to observe when a subscription lands. This revision is that signal.
    val revision by store.blocklistRevision.collectAsStateWithLifecycle()

    // Recomputed when a category is toggled, an update lands, or a subscription
    // finishes downloading — exactly when the headline should move.
    val blockedCount = remember(settings, remoteDomains, revision) {
        store.blockedDomainCount(settings)
    }

    val appBlockRevision by store.appBlockRevision.collectAsStateWithLifecycle()
    val enabledGroups = remember(appBlockRevision) { store.blockedAppGroups() }

    // Hoisted to the screen because two things now lead to the full tunnel — the three subject
    // rows and the app picker — and one dialog answered once is the point. Kept as the pending
    // action rather than a boolean so whatever asked for consent is what runs on accept.
    var confirming by remember { mutableStateOf<(() -> Unit)?>(null) }

    /**
     * Turning any app blocking on is where the full tunnel starts, so the warning goes here rather
     * than on each switch. Shown once — after that the user has made the trade knowingly.
     */
    fun withConsent(apply: () -> Unit) {
        if (store.fullTunnelAcknowledged) apply() else confirming = apply
    }

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
                // Another VPN being up is by far the most common cause, and naming it turns
                // "protection stopped" into something the user can act on. `anotherVpnActive`
                // existed for this and had no caller.
                byAnotherVpn = SafeWorldVpnService.anotherVpnActive(LocalContext.current),
                onAcknowledge = { requestPin(title, message, store::clearProtectionInterrupted) },
            )
        }

        ProtectionCard(running = running, requestPin = requestPin, onChange = onProtectionChange)

        // Private DNS routes every lookup over TLS to port 853, where this app cannot see it. Without
        // this warning the app would report protection as on while blocking nothing — the failure
        // mode worth shouting about.
        if (running && SafeWorldVpnService.isPrivateDnsActive(LocalContext.current)) {
            PrivateDnsWarning()
        }

        BlockedCountCard(count = blockedCount, running = running)

        // With no per-list rows, an accepted download that hasn't landed yet would
        // otherwise be invisible: the count simply wouldn't move, with nothing
        // saying why. Not a control — just the one sentence that explains a wait.
        SubscriptionPendingNote(subscriptions)

        Text(stringResource(R.string.block_more_title), style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                val subjects = subjectOrder()
                subjects.forEachIndexed { index, category ->
                    SubjectRow(
                        store = store,
                        settings = settings,
                        category = category,
                        enabledGroups = enabledGroups,
                        requestPin = requestPin,
                        withConsent = ::withConsent,
                    )
                    // Between rows only — a divider under the last one just underlines the card.
                    if (index < subjects.lastIndex) HorizontalDivider()
                }
            }
        }

        // What the three rows above don't cover: a specific app, or a site nobody has listed.
        Text(
            stringResource(R.string.block_further_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.appblock_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ChooseAppsRow(
                    store = store,
                    requestPin = requestPin,
                    withConsent = ::withConsent,
                )
                HorizontalDivider()
                AddWebsitesSection(store = store, requestPin = requestPin)
            }
        }

        // Not one of the three subject rows above, because it is not a subject:
        // it does not block anything, it covers people wherever they already
        // are. Blocking and blurring are independent — either works with the
        // other off.
        Text(stringResource(R.string.blur_title), style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                BlurSection(store = store, requestPin = requestPin)
            }
        }

        AlwaysOnSection()

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

    confirming?.let { apply ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.appblock_consent_title)) },
            text = { Text(stringResource(R.string.appblock_consent_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.acknowledgeFullTunnel()
                    confirming = null
                    apply()
                }) { Text(stringResource(R.string.appblock_consent_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.appblock_consent_cancel))
                }
            },
        )
    }
}

/**
 * The order the three subjects are offered in — games first, because that is the one people come
 * looking for and the one no other blocklist maintainer publishes.
 *
 * Written out rather than taken from `Categories.optional` so the order is a UI decision rather
 * than a side effect of the order someone happened to declare the categories in. Any optional
 * category not named here still renders, at the end, so adding a seventh cannot make it vanish.
 */
private fun subjectOrder(): List<com.safeworld.core.CategoryMeta> {
    val preferred = listOf(CategoryId.GAMES, CategoryId.SOCIAL, CategoryId.ENTERTAINMENT)
    return Categories.optional.sortedBy {
        preferred.indexOf(it.id).let { i -> if (i < 0) preferred.size else i }
    }
}

/**
 * One subject, one switch — the websites **and** the apps.
 *
 * **These used to be two rows and are deliberately one.** Somebody who wants social media gone
 * does not want it gone in Chrome and alive in the Instagram app; splitting it meant five switches
 * to express one intention, and a half-set state that looked like protection. The cost is real —
 * the app half needs the full tunnel — which is why turning one on routes through [withConsent]
 * rather than going straight through.
 *
 * **On only when both halves are on.** A row reading "on" while the app half was off would be
 * claiming to block Instagram on a phone where Instagram still worked, which is the one thing this
 * app must never say. An install left half-on by an older version therefore shows off, and one tap
 * puts it right.
 */
@Composable
private fun SubjectRow(
    store: SettingsStore,
    settings: com.safeworld.core.Settings,
    category: com.safeworld.core.CategoryMeta,
    enabledGroups: Set<AppGroup>,
    requestPin: RequestPin,
    withConsent: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val label = labelResFor(category.id)?.let { stringResource(it) } ?: category.label
    val description = descriptionResFor(category.id)?.let { stringResource(it) }
        ?: category.description
    // The bare noun, not the row's label — see `subjectResFor`.
    val subject = subjectResFor(category.id)?.let { stringResource(it) } ?: label
    val offTitle = stringResource(R.string.category_off_pin_title, subject)
    val offMessage = stringResource(R.string.category_off_pin_message)

    val group = AppGroup.entries.firstOrNull { it.category == category.id }
    val checked = settings.categories[category.id] == true &&
        (group == null || group in enabledGroups)

    ToggleRow(
        label = label,
        description = description,
        checked = checked,
        onCheckedChange = { on ->
            val apply = {
                store.update { it.copy(categories = it.categories + (category.id to on)) }
                if (group != null) {
                    store.setAppGroupBlocked(group, on)
                    // Routes are fixed at establish(), so moving between the DNS-only tunnel and
                    // the full one needs the tunnel rebuilt before it takes effect.
                    SafeWorldVpnService.refresh(context)
                }
            }
            when {
                !on -> requestPin(offTitle, offMessage, apply)
                group != null -> withConsent(apply)
                else -> apply()
            }
        },
    )
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
 *
 * **Says something different when protection is off**, because the same
 * sentence would be a lie: nothing is being blocked, and a card announcing 4.4
 * million blocked sites above a switch that is off is exactly the kind of
 * reassurance this app must never give. Off, it states the offer and points at
 * the switch; on, it states the fact and points at what else can be added.
 *
 * @param running whether the tunnel is actually up, not whether it is meant to
 *   be — the same signal `ProtectionCard` shows, so the two cannot disagree.
 */
@Composable
private fun BlockedCountCard(count: Int, running: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (running) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    if (running) R.string.blocked_headline else R.string.blocked_headline_off,
                    CountFormat.compact(count),
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (running) {
                // The exact figure, under the rounded headline. The headline is the claim someone
                // repeats; this is the number that makes it checkable, and it says the lists keep
                // moving without the user doing anything.
                Text(
                    stringResource(R.string.blocked_headline_detail, CountFormat.grouped(count)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.blocked_more_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
private fun InterruptionBanner(byAnotherVpn: Boolean, onAcknowledge: () -> Unit) {
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
                stringResource(
                    if (byAnotherVpn) R.string.interrupted_body_other_vpn
                    else R.string.interrupted_body
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.interrupted_dismiss))
            }
        }
    }
}

/**
 * Points the user at Android's own "Always-on VPN" and "Block connections without VPN" switches.
 *
 * **This is the only real answer to another VPN displacing us, and it cannot be automated.** Android
 * allows one active VPN at a time and offers no API to preempt another, and since Android 12 a
 * background app cannot start a foreground service — so no watchdog of ours can re-acquire the
 * tunnel by itself. What the OS does offer is these two settings: with them on, Android restarts
 * Safe World itself and, with lockdown, refuses to pass traffic while it is down. A displaced tunnel
 * then fails closed instead of open.
 *
 * There is no public API to read whether they are enabled for your own app (only a Device Owner can
 * query or set it), so this explains and links rather than pretending to know the state.
 */
@Composable
private fun AlwaysOnSection() {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.alwayson_unavailable)

    Text(stringResource(R.string.alwayson_title), style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.alwayson_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.alwayson_steps),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.alwayson_action))
            }
        }
    }
}

/**
 * Shown when Android's Private DNS is defeating the filter.
 *
 * Not dismissible and not PIN-gated: it is a statement of fact, not a setting. It disappears when the
 * user turns Private DNS off, and there is nothing for them to acknowledge in the meantime.
 */
@Composable
private fun PrivateDnsWarning() {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.alwayson_unavailable)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.private_dns_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.private_dns_body),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.private_dns_action))
            }
        }
    }
}

/**
 * Blurring people, as a row plus the choice of who.
 *
 * **Turning it on is free; turning it off costs the PIN.** The same rule every
 * other setting is held to — strengthening protection goes straight through,
 * weakening it does not. Switching the *target* is neither: it is a lateral
 * move between two equally covered states, so it goes through.
 *
 * The row can read "on" while nothing is running, and that is not the half-state
 * this product otherwise refuses. From Android 14 screen-capture consent is
 * granted per session and cannot be stored, so after a reboot the setting
 * legitimately survives and the capture does not. The row says exactly that and
 * offers one tap to start it again — the alternative, silently turning the
 * setting off on every restart, would be a promise quietly withdrawn.
 */
@Composable
private fun BlurSection(store: SettingsStore, requestPin: RequestPin) {
    val context = LocalContext.current
    val blur by store.blur.collectAsStateWithLifecycle()
    val running by BlurService.running.collectAsStateWithLifecycle()

    val offTitle = stringResource(R.string.blur_title)
    val offMessage = stringResource(R.string.category_off_pin_message)

    ToggleRow(
        label = stringResource(R.string.blur_title),
        description = stringResource(R.string.blur_description),
        checked = blur.enabled,
        onCheckedChange = { on ->
            if (on) {
                store.updateBlur { it.copy(enabled = true) }
                BlurConsentActivity.start(context)
            } else {
                requestPin(offTitle, offMessage) {
                    store.updateBlur { it.copy(enabled = false) }
                    BlurService.stop(context)
                }
            }
        },
    )

    if (blur.enabled) {
        if (!running) {
            HorizontalDivider()
            TextButton(
                onClick = { BlurConsentActivity.start(context) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.blur_restart_needed)) }
        }

        HorizontalDivider()
        Text(
            stringResource(R.string.blur_settings_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(Modifier.selectableGroup()) {
            for (target in BlurTarget.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = blur.target == target,
                            role = Role.RadioButton,
                            // Lateral, not a weakening: every option covers
                            // somebody, so none of them is a way out.
                            onClick = { store.updateBlur { it.copy(target = target) } },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = blur.target == target, onClick = null)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(stringResource(target.labelRes()))
                }
            }
        }

        Text(
            stringResource(R.string.blur_accuracy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * :core is a plain JVM module with no access to resources, so the label lives
 * here — the same split `CategoryStrings` already makes.
 */
private fun BlurTarget.labelRes(): Int = when (this) {
    BlurTarget.WOMEN -> R.string.blur_target_women
    BlurTarget.MEN -> R.string.blur_target_men
    BlurTarget.EVERYONE -> R.string.blur_target_everyone
}
