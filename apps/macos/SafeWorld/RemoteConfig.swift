import Foundation

/// Server-driven blocklist updates. Deliberately **not** exposed anywhere in
/// the app's UI or editable via `Settings` — end users can't see or change
/// this endpoint from the app itself; it ships baked into the binary and
/// only changes via an app update. Mirrors `RemoteConfig.swift` on iOS.
///
/// This hides the endpoint from the UI, not from the world: anyone inspecting
/// the compiled binary (e.g. `strings SafeWorld`) can still read this string,
/// same as any other client-embedded config. Don't rely on it for secrecy.
enum RemoteConfig {
    /// Published from https://github.com/jobayersajal1/safe-world-block-list-update via
    /// GitHub Pages. macOS fetches the **scrambled** variant, same as iOS: the
    /// hosts-file sinkhole needs literal domain names to write, so it can't
    /// use Android's one-way digests.
    ///
    /// Which bundled list this build shipped with, from
    /// `npm run baseline:snapshot`. **Update it whenever the bundled lists
    /// change**, or the app will fetch a delta computed against a list it
    /// doesn't have and miss whatever fell between the two.
    static let listBaseline = "3a7b028a5695"

    /// Only the domains added *since* `listBaseline` — the full list ships
    /// inside the app and is never published. A 404 means no delta exists for
    /// this baseline yet, which degrades to "no new domains".
    ///
    /// Empty disables automatic fetching.
    static let updateURL =
        "https://jobayersajal1.github.io/safe-world-block-list-update/delta-\(listBaseline).json"

    /// Minimum time between automatic fetches.
    static let updateIntervalHours: Double = 24
}
