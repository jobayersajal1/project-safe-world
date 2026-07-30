package com.safeworld.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeworld.app.R
import com.safeworld.app.SubscriptionStore
import com.safeworld.core.Subscriptions

/**
 * The offer to download the blocklists Safe World is not allowed to ship.
 *
 * **There is no per-list UI anywhere, and that is the design.** Someone who agrees
 * to download a blocklist wants what it blocks; a switch per feed would be a way
 * to turn parts of gambling or adult blocking back off, which is precisely the
 * control this app refuses to offer for its own categories. Once accepted these
 * behave like `list1`–`list3`: folded into the blocked count, no rows, no toggles.
 * Consent is the whole mechanism.
 *
 * **The wording names no categories.** "Extra gambling list" on a home screen tells
 * anyone holding the phone what its owner is blocking, and this is often a shared
 * or family device. A count and a size say everything the decision needs.
 *
 * Shown after the PIN and recovery code exist. These feeds belong to the two
 * categories with no off switch, so there must be no window where the strongest
 * protection in the app can be undone with one tap.
 *
 * Skipping is not persisted — see `SubscriptionStore.accepted`. It asks again next
 * launch, because one mistimed tap should not permanently cost someone half the
 * protection available to them.
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
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            // Size and terms before the choice, not discovered on a metered
            // connection afterwards. "Personal use only" is oisd's actual
            // condition, and the honest reason this is a download rather than
            // something we could have bundled.
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

/**
 * One line explaining why the blocked count hasn't moved yet.
 *
 * The offer is accepted but the lists are still arriving — or the last attempt
 * failed. Without this the count simply sits where it was and the user has no way
 * to tell a slow download from a broken feature. Deliberately not a control: there
 * is nothing here to toggle, retry, or configure, because the worker already
 * retries on its own schedule.
 *
 * Renders nothing once the lists are in place, since at that point the count says
 * it.
 */
@Composable
fun SubscriptionPendingNote(subscriptions: SubscriptionStore) {
    val accepted by subscriptions.accepted.collectAsStateWithLifecycle()
    val status by subscriptions.status.collectAsStateWithLifecycle()
    val enabled by subscriptions.enabled.collectAsStateWithLifecycle()

    if (!accepted) return

    val landed = enabled.count { (status[it]?.domainCount ?: 0) > 0 }
    if (landed == enabled.size) return

    val anyFailed = enabled.any { status[it]?.failed == true }
    Text(
        stringResource(
            if (anyFailed) R.string.subscription_pending_retry else R.string.subscription_pending
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
