import SwiftUI
import SafeWorldCore

/// First-run setup, one question at a time — the macOS half of Android's `OnboardingScreen` and
/// iOS's `OnboardingView`, and deliberately the same questions in the same order.
///
/// **Why a menu-bar app needs this at all.** Before it, a fresh install put a shield in the menu
/// bar and waited. Everything optional was off, the DNS daemon was uninstalled, and nothing said
/// so — the app was working exactly as designed and looked, from the outside, like it did nothing.
/// The phones had already learned this lesson; the Mac had not.
///
/// **Three of the phones' steps are absent, and none is an oversight.** There is no language step:
/// this app is English-only (see `CountFormat`). There is no PIN step: macOS is the self-control
/// build, stated as such on the main screen. And there is no uninstall guard, which has no macOS
/// equivalent short of MDM.
///
/// What macOS has instead is a step the phones do not need: the DNS daemon is what takes blocking
/// from the hosts file's capped list to the full corpus in every app, it needs an admin password,
/// and an app that never mentions it is an app that quietly blocks a thirtieth of what it claims.
struct SetupView: View {

    @ObservedObject var store: SettingsStore
    @ObservedObject var daemon: DaemonController

    /// Called when the last step is acknowledged, so the window can close itself.
    let onFinish: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header

            switch store.onboardingStep {
            case Step.welcome:
                welcomeStep

            case Step.games:
                subjectStep(
                    .games,
                    title: "Block games?",
                    body: "Game portals, browser games, and gaming platforms."
                )

            case Step.social:
                subjectStep(
                    .social,
                    title: "Block social media?",
                    body: "Facebook, Instagram, X/Twitter, TikTok, and similar feeds — including dating sites."
                )

            case Step.entertainment:
                subjectStep(
                    .entertainment,
                    title: "Block entertainment?",
                    body: "YouTube, Netflix, and other streaming and video sites."
                )

            case Step.daemon:
                daemonStep

            default:
                finishStep
            }

            Spacer(minLength: 0)
        }
        .padding(28)
        .frame(width: 460, height: 380, alignment: .topLeading)
    }

    // MARK: Scaffolding

    /// Answering questions with no idea how many are left is how people abandon setup
    /// half-configured.
    private var header: some View {
        let total = Step.numbered.count
        let shown = min(max((Step.numbered.firstIndex(of: store.onboardingStep) ?? 0) + 1, 1), total)

        return VStack(alignment: .leading, spacing: 6) {
            Text("Step \(shown) of \(total)")
                .font(.caption)
                .foregroundStyle(.secondary)
            ProgressView(value: Double(shown), total: Double(total))
                .tint(Brand.accent)
        }
    }

    private func title(_ text: String) -> some View {
        Text(text).font(.title2.weight(.semibold))
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Steps

    /// States what is already covered before asking for anything.
    ///
    /// The three mandatory lists are the reason the app exists, so setup does not ask about them —
    /// it reports them. Asking would imply an answer of "no" exists.
    private var welcomeStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title("Safe World is on")
            message(
                "Scams and malware, gambling, and adult content are blocked from now on. "
                + "Those three aren't optional — blocking them is what this app is for."
            )
            message("The next few questions are about what else you'd like gone.")

            Button("Continue") { store.onboardingStep = Step.games }
                .buttonStyle(.borderedProminent)
                .tint(Brand.accent)
                .keyboardShortcut(.defaultAction)
        }
    }

    /// One optional subject, asked as a question.
    private func subjectStep(_ id: CategoryId, title titleText: String, body bodyText: String) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            title(titleText)
            message(bodyText)

            HStack {
                Button("Block it") { answer(id, blocked: true) }
                    .buttonStyle(.borderedProminent)
                    .tint(Brand.accent)
                    .keyboardShortcut(.defaultAction)
                Button("Leave it") { answer(id, blocked: false) }
                    .buttonStyle(.bordered)
            }
        }
    }

    /// Writes `false` rather than just advancing.
    ///
    /// Only ever setting `true` made "leave it" mean "keep whatever was there", so a second run of
    /// setup could not turn anything back off. Same fix as iOS's — the answer has to mean what it
    /// says.
    private func answer(_ id: CategoryId, blocked: Bool) {
        store.update { $0.categories[id] = blocked }
        store.onboardingStep += 1
    }

    /// The one step that is macOS-specific, and the one that actually decides how much this app
    /// blocks. See `MenuView.deviceWideSection` for the same trade, stated the same way.
    private var daemonStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title("Block across the whole Mac?")

            if daemon.isRunning {
                message("Device-wide blocking is on. Every app on this Mac is filtered.")
            } else {
                message(
                    "Right now blocking is limited to a smaller list, and only where the hosts "
                    + "file is read. Turning this on filters every app on this Mac against the "
                    + "full list — about thirty times as many sites."
                )
                message("It asks for your password once, because it installs a system service.")
            }

            HStack {
                if daemon.isRunning {
                    Button("Continue") { store.onboardingStep = Step.finish }
                        .buttonStyle(.borderedProminent)
                        .tint(Brand.accent)
                        .keyboardShortcut(.defaultAction)
                } else {
                    Button("Turn it on") {
                        Task {
                            await daemon.install(settings: store.settings)
                            // Advances either way. Stranding setup on this step because the user
                            // cancelled the password prompt would hold the whole app hostage to
                            // the one thing here that can legitimately be declined.
                            store.onboardingStep = Step.finish
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Brand.accent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(daemon.isBusy)

                    Button("Not now") { store.onboardingStep = Step.finish }
                        .buttonStyle(.bordered)
                        .disabled(daemon.isBusy)
                }

                if daemon.isBusy { ProgressView().controlSize(.small) }
            }

            if let error = daemon.lastError, error != "Cancelled." {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .onAppear { daemon.refresh() }
    }

    private var finishStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title("You're set up")
            message(
                "Safe World lives in the menu bar — click the shield to change anything, "
                + "or to turn protection off."
            )

            Button("Done") {
                store.completeOnboarding()
                onFinish()
            }
            .buttonStyle(.borderedProminent)
            .tint(Brand.accent)
            .keyboardShortcut(.defaultAction)
        }
    }
}

private enum Step {
    static let welcome = 0
    static let games = 1
    static let social = 2
    static let entertainment = 3
    static let daemon = 4
    static let finish = 5

    /// The steps that carry a "step N of M" header, in order. Every step is numbered here — unlike
    /// iOS, this wizard has no PIN detour to skip over — but it stays a list so adding one later
    /// cannot silently renumber the rest.
    static let numbered = [welcome, games, social, entertainment, daemon, finish]
}
