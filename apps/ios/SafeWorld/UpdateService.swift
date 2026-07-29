import Foundation
import SafeWorldCore

/// Checks whether a newer build has been published, and tells the user where to
/// get it.
///
/// **iOS cannot install its own update, and this deliberately doesn't pretend
/// otherwise.** There is no API for an app to fetch and install an `.ipa`;
/// distribution goes through the App Store, TestFlight, or MDM, and nothing
/// else. So this checks the same GitHub release every other platform checks and
/// then hands off to the release page in Safari, where the user can follow
/// whatever install route applies to their build. When the app does reach the
/// App Store, the honest change here is to point `openDownloadPage` at the
/// store listing — the checking half already works as-is.
@MainActor
final class UpdateService: ObservableObject {
    enum State: Equatable {
        case idle
        case checking
        /// Checked, and this build is the newest published one.
        case upToDate
        case available(AvailableUpdate)
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    /// The running build's `CFBundleShortVersionString`, shown in Settings and
    /// compared against the newest release.
    let installedVersion: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
    }()

    /// In memory rather than persisted: the throttle only needs to stop a burst
    /// of checks within one run, and a fresh launch checking once is exactly
    /// what we want.
    private static var lastCheckedAt: Date?

    /// Looks for a newer release. `force` runs even when one ran recently — the
    /// throttle exists so reopening Settings doesn't spend the unauthenticated
    /// rate limit, but a button the user pressed should always do something.
    func check(force: Bool = false) {
        if case .checking = state { return }
        if !force, let last = Self.lastCheckedAt,
           Date().timeIntervalSince(last) < ReleaseChecker.checkIntervalHours * 3600 {
            return
        }

        state = .checking
        Task {
            do {
                let release = try await fetchLatest()
                Self.lastCheckedAt = Date()
                // No asset extension: nothing on a release is installable from
                // inside an iOS app, so only the page URL is of any use here.
                let update = ReleaseChecker.select(
                    release: release,
                    currentVersion: installedVersion,
                    assetExtension: nil
                )
                state = update.map(State.available) ?? .upToDate
            } catch {
                // Network, HTTP, and malformed JSON all mean the same thing to
                // the user: we couldn't ask right now.
                state = .failed("Couldn't check for updates. Check your connection and try again.")
            }
        }
    }

    private func fetchLatest() async throws -> GitHubRelease {
        var request = URLRequest(url: ReleaseChecker.latestReleaseURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        // GitHub rejects API requests that don't identify themselves.
        request.setValue("SafeWorld-iOS", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return try ReleaseChecker.parse(data)
    }
}
