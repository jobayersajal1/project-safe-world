import SafeWorldCore
import SwiftUI
import UIKit

/// First-run setup, one question at a time — the iOS half of Android's
/// `OnboardingScreen`, and deliberately the same questions in the same order.
///
/// **Language is first** because every question after it is a sentence the user
/// has to read to answer honestly.
///
/// **Two of Android's steps are absent, and neither is an oversight.** There is
/// no uninstall guard: device admin has no iOS equivalent outside MDM, which is
/// why the PIN lives in the Keychain instead — reinstalling to shed it lands
/// back at the same prompt. And there is no extra-lists download: the
/// subscription feeds are an Android feature, because Safari's content blocker
/// is already at its rule ceiling without them.
///
/// What iOS has instead is a step Android does not need: the Safari content
/// blocker is off until the user turns it on in system Settings, and an app that
/// never mentions that is an app that silently blocks nothing.
struct OnboardingView: View {

    @ObservedObject var store: SettingsStore
    @ObservedObject var pins: PinStore

    private let apps = BlockedAppStore()

    @State private var recoveryCode: String?

    var body: some View {
        NavigationStack {
            switch store.onboardingStep {
            case Step.language:
                languageStep

            case Step.pin:
                QuestionStep(
                    step: Step.pin,
                    title: L("onb_pin_title"),
                    message: L("onb_pin_body"),
                    yes: L("onb_pin_yes"),
                    no: L("onb_pin_no"),
                    onYes: { store.onboardingStep = Step.pinEntry },
                    // Declining skips the recovery code too — there is nothing
                    // to recover — so it jumps the detour rather than entering it.
                    onNo: { store.onboardingStep = Step.social }
                )

            case Step.pinEntry:
                pinEntryStep

            case Step.social:
                subjectStep(.social, group: .social, title: L("onb_social_title"))

            case Step.games:
                subjectStep(.games, group: .games, title: L("onb_games_title"))

            case Step.entertainment:
                subjectStep(
                    .entertainment,
                    group: .entertainment,
                    title: L("onb_entertainment_title")
                )

            case Step.safari:
                QuestionStep(
                    step: Step.safari,
                    title: L("onb_safari_title"),
                    message: L("safari_enable_help"),
                    yes: L("safari_enable_action"),
                    no: L("onb_pin_no"),
                    onYes: {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                        // Advances either way: the user finishes setup in
                        // Settings and comes back, and stranding them on this
                        // screen until they do would be worse than trusting them.
                        store.onboardingStep = Step.finish
                    },
                    onNo: { store.onboardingStep = Step.finish }
                )

            default:
                QuestionStep(
                    step: Step.finish,
                    title: L("onb_finish_title"),
                    message: L("onb_finish_body_ios"),
                    yes: L("onb_finish_action_ios"),
                    no: nil,
                    onYes: { store.completeOnboarding() },
                    onNo: {}
                )
            }
        }
    }

    private var languageStep: some View {
        StepScaffold(step: Step.language, title: L("onb_language_title"), message: L("onb_language_body")) {
            Form {
                ForEach(Language.supported, id: \.self) { tag in
                    Button {
                        // Applied immediately, so the effect is visible before
                        // moving on — the fastest way to find out you picked the
                        // wrong one.
                        store.language = tag
                    } label: {
                        HStack {
                            Text(Language.displayName(tag)).foregroundStyle(.primary)
                            Spacer()
                            if tag == store.language {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                }
            }
            .frame(minHeight: 300)

            Button(L("onb_continue")) { store.onboardingStep = Step.pin }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private var pinEntryStep: some View {
        if !pins.hasPin {
            SetPinView(
                title: L("pin_setup_title"),
                confirmLabel: L("pin_save"),
                onPinChosen: { pins.setPin($0) },
                onCancel: { store.onboardingStep = Step.social }
            )
        } else {
            // A PIN with no way back is one forgotten number away from a
            // reinstall, so the code is not optional once a PIN exists.
            RecoveryCodeView(
                code: recoveryCode ?? "",
                isReplacement: false,
                onAcknowledge: { store.onboardingStep = Step.social }
            )
            // Issued in a task, never in `body`: minting writes to the Keychain,
            // and doing that while rendering hands out a code that is already
            // superseded by the next render.
            .task {
                if recoveryCode == nil { recoveryCode = pins.issueRecoveryCode() }
            }
        }
    }

    /// One subject — the websites *and* the apps, the pairing the home screen makes.
    private func subjectStep(
        _ id: CategoryId,
        group: AppGroups.Group,
        title: String
    ) -> some View {
        QuestionStep(
            step: store.onboardingStep,
            title: title,
            message: L("category_\(id.rawValue)_description"),
            note: L("onb_apps_note_ios"),
            yes: L("onb_block_yes"),
            no: L("onb_block_no"),
            onYes: {
                store.update { $0.categories[id] = true }
                apps.setGroup(group, blocked: true)
                apps.fullTunnelAcknowledged = true
                store.onboardingStep += 1
            },
            onNo: { store.onboardingStep += 1 }
        )
    }
}

private enum Step {
    static let language = 0
    static let pin = 1
    static let pinEntry = 2
    static let social = 3
    static let games = 4
    static let entertainment = 5
    static let safari = 6
    static let finish = 7
}

/// The steps that carry a "step N of M" header, in order.
///
/// Derived from this rather than from the raw index because the PIN detour draws
/// no header — counting raw indices numbered the questions 1, 2, 4, 5.
private let numberedSteps = [
    Step.language, Step.pin, Step.social, Step.games,
    Step.entertainment, Step.safari, Step.finish,
]

private struct QuestionStep: View {
    let step: Int
    let title: String
    let message: String
    var note: String?
    let yes: String
    let no: String?
    let onYes: () -> Void
    let onNo: () -> Void

    var body: some View {
        StepScaffold(step: step, title: title, message: message) {
            if let note {
                Text(note).font(.footnote).foregroundStyle(.secondary)
            }
            Button(yes, action: onYes)
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            if let no {
                Button(no, action: onNo)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct StepScaffold<Content: View>: View {
    let step: Int
    let title: String
    let message: String
    @ViewBuilder let content: Content

    var body: some View {
        let total = numberedSteps.count
        let shown = min(max((numberedSteps.firstIndex(of: step) ?? 0) + 1, 1), total)

        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Answering questions with no idea how many are left is how
                // people abandon setup half-configured.
                Text(L("onb_step", "\(shown)", "\(total)"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                ProgressView(value: Double(shown), total: Double(total))

                Text(title).font(.title.weight(.semibold))
                Text(message).font(.body)

                content
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
