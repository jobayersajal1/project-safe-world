import AppKit
import Foundation
import SafeWorldCore

/// Checks for a newer build, downloads its disk image, and opens it.
///
/// Stops at "the .dmg is mounted and showing", deliberately. Replacing a
/// running app bundle in place is what Sparkle exists to do, and doing it
/// safely means a signed, notarized build so the replacement can be verified —
/// this app is ad-hoc signed (see the README), so a self-replacing updater here
/// would be swapping the binary with nothing checking what it swapped in.
/// Mounting the image and letting the user drag it to Applications keeps
/// Gatekeeper in the loop and is one step for them.
@MainActor
final class UpdateService: ObservableObject {
    enum State: Equatable {
        case idle
        case checking
        /// Checked, and this build is the newest published one.
        case upToDate
        case available(AvailableUpdate)
        /// `fraction` is 0...1, or nil while the size is still unknown.
        case downloading(AvailableUpdate, fraction: Double?)
        /// Downloaded and handed to Finder.
        case downloaded(AvailableUpdate)
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    /// The running build's `CFBundleShortVersionString`, shown in the menu and
    /// compared against the newest release.
    let installedVersion: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
    }()

    private var lastCheckedAt: Date?

    // MARK: Checking

    /// Looks for a newer release. `force` runs even when one ran recently — the
    /// throttle exists so reopening the menu doesn't spend the unauthenticated
    /// rate limit, but a button the user pressed should always do something.
    func check(force: Bool = false) {
        if case .checking = state { return }
        if case .downloading = state { return }
        if !force, let last = lastCheckedAt,
           Date().timeIntervalSince(last) < ReleaseChecker.checkIntervalHours * 3600 {
            return
        }

        state = .checking
        Task {
            do {
                let release = try await fetchLatest()
                lastCheckedAt = Date()
                let update = ReleaseChecker.select(
                    release: release,
                    currentVersion: installedVersion,
                    assetExtension: ".dmg"
                )
                state = update.map(State.available) ?? .upToDate
            } catch {
                // Network, HTTP, and malformed JSON all mean the same thing to
                // the user: we couldn't ask right now.
                state = .failed("Couldn't check for updates.")
            }
        }
    }

    private func fetchLatest() async throws -> GitHubRelease {
        var request = URLRequest(url: ReleaseChecker.latestReleaseURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        // GitHub rejects API requests that don't identify themselves.
        request.setValue("SafeWorld-macOS", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return try ReleaseChecker.parse(data)
    }

    // MARK: Downloading

    /// Downloads the disk image to ~/Downloads and opens it. A release with no
    /// .dmg attached (the .apk and .exe live in the same release) still has a
    /// web page worth going to, so that case opens the page instead of failing.
    func download(_ update: AvailableUpdate) {
        guard let assetURL = update.assetURL else {
            NSWorkspace.shared.open(update.pageURL)
            return
        }
        if case .downloading = state { return }

        state = .downloading(update, fraction: nil)
        Task {
            do {
                let file = try await downloadToDownloadsFolder(assetURL, update: update)
                state = .downloaded(update)
                // Mounts the image and brings its window up, which is where the
                // drag-to-Applications step happens.
                NSWorkspace.shared.open(file)
            } catch {
                state = .failed("The download didn't finish.")
            }
        }
    }

    func openReleasePage(_ update: AvailableUpdate) {
        NSWorkspace.shared.open(update.pageURL)
    }

    private func downloadToDownloadsFolder(
        _ url: URL,
        update: AvailableUpdate
    ) async throws -> URL {
        let (bytes, response) = try await URLSession.shared.bytes(from: url)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        // The release metadata's size is the more reliable of the two: the asset
        // download redirects, and the final response doesn't always carry a length.
        let total = update.sizeBytes > 0 ? update.sizeBytes : response.expectedContentLength

        var data = Data()
        if total > 0 { data.reserveCapacity(Int(total)) }
        var lastReported = 0.0

        for try await byte in bytes {
            data.append(byte)
            guard total > 0 else { continue }
            let fraction = Double(data.count) / Double(total)
            // Republishing per byte would repaint the menu tens of thousands of
            // times; 1% steps are past what the eye resolves on a short bar.
            if fraction - lastReported >= 0.01 {
                lastReported = fraction
                state = .downloading(update, fraction: min(fraction, 1))
            }
        }

        let downloads = try FileManager.default.url(
            for: .downloadsDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        let target = downloads.appendingPathComponent(url.lastPathComponent)
        // Replace a previous attempt rather than piling up "file-1.dmg".
        try? FileManager.default.removeItem(at: target)
        try data.write(to: target)
        return target
    }
}
