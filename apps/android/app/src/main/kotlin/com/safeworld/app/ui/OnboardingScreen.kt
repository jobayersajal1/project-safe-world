package com.safeworld.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.safeworld.app.LocaleHelper
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.app.SubscriptionStore
import com.safeworld.core.AppGroups.AppGroup
import com.safeworld.core.CategoryId

/**
 * First-run setup, one question at a time.
 *
 * **The point is that nobody should have to find a toggle.** The home screen is a
 * good place to change your mind and a bad place to make ten decisions you did
 * not know you had to make, so setup asks them in order and applies every answer
 * — categories, app blocking, the extra lists, the PIN, uninstall protection —
 * then turns protection on at the end.
 *
 * **Language is first, and that is not cosmetic.** Every question after it is a
 * sentence the user has to read to answer honestly; asking in a language they
 * do not speak turns the whole wizard into guesswork.
 *
 * **Three answers cannot be applied by us, only requested.** Android shows its
 * own screen for the VPN connection, for notifications, and for device admin,
 * and there is no permission or flag that skips any of them. So the wizard
 * gathers the cheap answers first and spends the system prompts at the end,
 * where they read as "finishing setup" rather than as three interruptions.
 * Always-on VPN is not offered at all: only a Device Owner can set it, so an
 * ordinary app can point at the setting and nothing more — see `AlwaysOnSection`.
 *
 * The step index lives in [SettingsStore], not in `remember`: choosing a
 * language recreates the activity, which would otherwise send the user back to
 * question one.
 */
@Composable
fun OnboardingScreen(
    store: SettingsStore,
    subscriptions: SubscriptionStore,
    onEnableUninstallProtection: () -> Unit,
    onFinish: () -> Unit,
) {
    val step by store.onboardingStep.collectAsStateWithLifecycle()
    val hasPin by store.hasPin.collectAsStateWithLifecycle()

    // Issued in an effect, never in composition: `issueRecoveryCode` writes to
    // storage, so calling it inline would mint a fresh code on every
    // recomposition and show the user one that is already superseded.
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hasPin) {
        if (hasPin && recoveryCode == null) recoveryCode = store.issueRecoveryCode()
    }

    fun advance() = store.setOnboardingStep(step + 1)

    when (step) {
        STEP_LANGUAGE -> LanguageStep(store, onNext = ::advance)

        STEP_PIN -> QuestionStep(
            step = step,
            title = stringResource(R.string.onb_pin_title),
            body = stringResource(R.string.onb_pin_body),
            yesLabel = stringResource(R.string.onb_pin_yes),
            noLabel = stringResource(R.string.onb_pin_no),
            onYes = { store.setOnboardingStep(STEP_PIN_ENTRY) },
            // Declining skips the PIN *and* the recovery code — there is nothing
            // to recover — so it jumps the detour rather than stepping into it.
            onNo = { store.setOnboardingStep(STEP_SOCIAL) },
        )

        // The detour: choose a PIN, then save the code it comes with.
        STEP_PIN_ENTRY -> when {
            !hasPin -> SetPinScreen(onPinChosen = store::setPin)

            // A PIN with no way back is one forgotten number away from "clear
            // app data", which discards every setting and the uninstall
            // friction with it. So the code is not optional once a PIN exists.
            recoveryCode != null -> RecoveryCodeScreen(
                code = recoveryCode!!,
                isReplacement = false,
                onAcknowledge = { store.setOnboardingStep(STEP_SOCIAL) },
            )

            else -> Unit
        }

        STEP_SOCIAL -> SubjectStep(
            store = store,
            step = step,
            category = CategoryId.SOCIAL,
            group = AppGroup.SOCIAL,
            title = stringResource(R.string.onb_social_title),
            body = stringResource(R.string.category_list4_description),
            onDone = ::advance,
        )

        STEP_GAMES -> SubjectStep(
            store = store,
            step = step,
            category = CategoryId.GAMES,
            group = AppGroup.GAMES,
            title = stringResource(R.string.onb_games_title),
            body = stringResource(R.string.category_list6_description),
            onDone = ::advance,
        )

        STEP_ENTERTAINMENT -> SubjectStep(
            store = store,
            step = step,
            category = CategoryId.ENTERTAINMENT,
            group = AppGroup.ENTERTAINMENT,
            title = stringResource(R.string.onb_entertainment_title),
            body = stringResource(R.string.category_list5_description),
            onDone = ::advance,
        )

        // Reuses the standalone offer rather than restating it: the count and the
        // download size are computed from `Subscriptions`, and two screens
        // quoting different megabytes for the same download is how one of them
        // ends up wrong.
        STEP_EXTRA -> SubscriptionOfferScreen(
            onAccept = { subscriptions.acceptAll(); advance() },
            onSkip = ::advance,
        )

        STEP_UNINSTALL -> QuestionStep(
            step = step,
            title = stringResource(R.string.onb_uninstall_title),
            body = stringResource(R.string.onb_uninstall_body),
            yesLabel = stringResource(R.string.onb_uninstall_yes),
            noLabel = stringResource(R.string.onb_block_no),
            // Android's own confirmation follows. Advancing either way: the
            // system screen's answer is not ours to wait on, and someone who
            // declines it there must still reach the end of setup.
            onYes = { onEnableUninstallProtection(); advance() },
            onNo = ::advance,
        )

        else -> QuestionStep(
            step = step,
            title = stringResource(R.string.onb_finish_title),
            body = stringResource(R.string.onb_finish_body),
            yesLabel = stringResource(R.string.onb_finish_action),
            noLabel = null,
            onYes = {
                store.completeOnboarding()
                onFinish()
            },
            onNo = {},
        )
    }
}

private const val STEP_LANGUAGE = 0
private const val STEP_PIN = 1
private const val STEP_PIN_ENTRY = 2
private const val STEP_SOCIAL = 3
private const val STEP_GAMES = 4
private const val STEP_ENTERTAINMENT = 5
private const val STEP_EXTRA = 6
private const val STEP_UNINSTALL = 7
private const val STEP_FINISH = 8

/**
 * The steps that carry a "step N of M" header, in order.
 *
 * Derived from this rather than from the raw index because two steps do not draw
 * the header: the PIN detour is optional, and the extra-lists offer reuses a
 * standalone screen. Counting raw indices numbered the questions 1, 2, 4, 5 —
 * and made the last one "step 9 of 8".
 */
private val NUMBERED_STEPS = listOf(
    STEP_LANGUAGE,
    STEP_PIN,
    STEP_SOCIAL,
    STEP_GAMES,
    STEP_ENTERTAINMENT,
    STEP_UNINSTALL,
    STEP_FINISH,
)

@Composable
private fun LanguageStep(store: SettingsStore, onNext: () -> Unit) {
    val context = LocalContext.current
    val current by store.language.collectAsStateWithLifecycle()

    StepScaffold(
        step = STEP_LANGUAGE,
        title = stringResource(R.string.onb_language_title),
        body = stringResource(R.string.onb_language_body),
    ) {
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                for (tag in LocaleHelper.SUPPORTED) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // Picking one applies it immediately, which recreates
                            // the activity. The wizard stays on this step so the
                            // change is visible before moving on — the fastest
                            // way to find out you picked the wrong one.
                            .selectable(selected = tag == current) { store.setLanguage(tag) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == current, onClick = null)
                        Text(
                            LocaleHelper.displayName(tag, context),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.padding(4.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_continue))
        }
    }
}

/**
 * One subject — the websites *and* the apps, the same pairing the home screen
 * makes. Answering yes here is also the moment the full-tunnel cost is accepted,
 * so it is acknowledged now and never asked about again.
 */
@Composable
private fun SubjectStep(
    store: SettingsStore,
    step: Int,
    category: CategoryId,
    group: AppGroup,
    title: String,
    body: String,
    onDone: () -> Unit,
) {
    QuestionStep(
        step = step,
        title = title,
        body = body,
        note = stringResource(R.string.onb_apps_note),
        yesLabel = stringResource(R.string.onb_block_yes),
        noLabel = stringResource(R.string.onb_block_no),
        onYes = {
            store.update { it.copy(categories = it.categories + (category to true)) }
            store.setAppGroupBlocked(group, true)
            store.acknowledgeFullTunnel()
            onDone()
        },
        // Writes `false` rather than just advancing. Only ever setting `true`
        // made "no" mean "leave whatever was there", so a re-run of setup could
        // not turn anything back off — the answer has to mean what it says.
        onNo = {
            store.update { it.copy(categories = it.categories + (category to false)) }
            store.setAppGroupBlocked(group, false)
            onDone()
        },
    )
}

@Composable
private fun QuestionStep(
    step: Int,
    title: String,
    body: String,
    yesLabel: String,
    noLabel: String?,
    onYes: () -> Unit,
    onNo: () -> Unit,
    note: String? = null,
) {
    StepScaffold(step = step, title = title, body = body) {
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(4.dp))
        Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) { Text(yesLabel) }
        if (noLabel != null) {
            OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) { Text(noLabel) }
        }
    }
}

@Composable
private fun StepScaffold(
    step: Int,
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            // Shown outside the Scaffold, so it gets no inset padding of its own
            // and the step counter would otherwise sit under the status bar.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Answering questions with no idea how many are left is how people
        // abandon setup half-configured.
        val total = NUMBERED_STEPS.size
        val shown = (NUMBERED_STEPS.indexOf(step) + 1).coerceIn(1, total)
        Text(
            stringResource(R.string.onb_step, shown, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { shown.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.padding(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.padding(4.dp))
        content()
    }
}
