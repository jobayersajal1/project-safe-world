package com.safeworld.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.safeworld.app.R
import com.safeworld.app.SubscriptionStore
import com.safeworld.core.Subscriptions

/**
 * Rows for the blocklists Safe World is not allowed to ship, sitting beside the
 * Social media and Entertainment switches under "want to block more?".
 *
 * They look like the other opt-in rows because they behave like them: adding is
 * free, removing costs the PIN. What is different, and what the wording has to
 * carry, is that enabling one downloads several megabytes from a third party and
 * accepts that party's terms. The licence and the size are shown **before** the
 * switch is touched, never after.
 */
@Composable
fun SubscriptionRows(
    subscriptions: SubscriptionStore,
    requestPin: RequestPin,
    onChanged: () -> Unit,
) {
    val enabled by subscriptions.enabled.collectAsStateWithLifecycle()
    val status by subscriptions.status.collectAsStateWithLifecycle()
    val context = LocalContext.current

    for (subscription in Subscriptions.ALL) {
        val isOn = subscription.id in enabled
        val state = status[subscription.id]
        val offTitle = stringResource(R.string.subscription_off_pin_title)
        val offMessage = stringResource(R.string.subscription_off_pin_message)

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(labelResFor(subscription.id)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            R.string.subscription_description,
                            CountFormat.compact(subscription.approxDomains),
                            subscription.publisher,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isOn,
                    onCheckedChange = { on ->
                        val apply = {
                            subscriptions.setEnabled(subscription.id, on)
                            onChanged()
                        }
                        if (on) apply() else requestPin(offTitle, offMessage, apply)
                    },
                )
            }

            // Below the row: what it costs, how it is going, or why it failed.
            when {
                state?.inProgress == true -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(2.dp))
                    Text(
                        stringResource(R.string.subscription_downloading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state?.failed == true -> Text(
                    stringResource(R.string.subscription_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                isOn && state != null && state.domainCount > 0 -> Text(
                    stringResource(
                        R.string.subscription_active,
                        CountFormat.grouped(state.domainCount),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                else -> Text(
                    // The size and the licence, stated before the tap rather than
                    // discovered on a metered connection afterwards.
                    stringResource(
                        R.string.subscription_terms,
                        Formatter.formatShortFileSize(context, subscription.approxBytes),
                        subscription.license,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}

private fun labelResFor(id: String): Int = when (id) {
    "hagezi-gambling" -> R.string.subscription_hagezi_gambling
    "hagezi-nsfw" -> R.string.subscription_hagezi_nsfw
    "oisd-nsfw" -> R.string.subscription_oisd_nsfw
    // A subscription added to the catalog without a label: show the id rather
    // than crashing, so a missing string is a cosmetic bug not a broken screen.
    else -> R.string.subscription_unnamed
}

/**
 * The one-time setup offer, shown after the PIN and recovery code exist.
 *
 * These lists roughly double what the app blocks, and a switch under "want to
 * block more?" is a switch most people never find — so the choice is put in front
 * of them once. It is **not** default-on: enabling downloads several megabytes
 * from third parties and accepts their terms, and doing either without asking
 * would be both legally weaker and rude on a metered connection.
 *
 * Shown after the PIN deliberately. These feeds belong to the two categories with
 * no off switch, so once one is on, turning it off costs the PIN — and there must
 * be no window where the strongest protection in the app can be undone with one
 * tap.
 */
@Composable
fun SubscriptionOfferScreen(
    onAccept: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val totalDomains = Subscriptions.ALL.sumOf { it.approxDomains }
    val totalBytes = Subscriptions.ALL.sumOf { it.approxBytes }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Shown outside the Scaffold, so it gets no inset padding of its own
            // and the title would otherwise sit under the status bar.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.subscription_offer_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(
                R.string.subscription_offer_body,
                CountFormat.compact(totalDomains),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (subscription in Subscriptions.ALL) {
                    Column {
                        Text(
                            stringResource(labelResFor(subscription.id)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(
                                R.string.subscription_offer_item,
                                CountFormat.grouped(subscription.approxDomains),
                                subscription.publisher,
                                subscription.license,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            // Says who is being trusted, where the download comes from, and what
            // it costs — before the choice, not after it.
            stringResource(
                R.string.subscription_offer_note,
                Formatter.formatShortFileSize(context, totalBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.subscription_offer_accept))
        }
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.subscription_offer_skip))
        }
    }
}
