import Foundation

/// A published release newer than what's installed.
public struct AvailableUpdate: Equatable, Sendable {
    /// As published, e.g. "0.2.0" — the leading "v" of the tag is already stripped.
    public let version: String
    /// Direct link to this platform's installable asset, or nil when the
    /// release carries none. iOS is always nil by nature: the OS has no way to
    /// install an app from anywhere but the App Store, so there is nothing to
    /// point at. See `pageURL`.
    public let assetURL: URL?
    /// Bytes, for showing the download size before committing to it. 0 when unknown.
    public let sizeBytes: Int64
    /// The release's own page — where a user goes when there's nothing to install directly.
    public let pageURL: URL

    public init(version: String, assetURL: URL?, sizeBytes: Int64, pageURL: URL) {
        self.version = version
        self.assetURL = assetURL
        self.sizeBytes = sizeBytes
        self.pageURL = pageURL
    }
}

/// The subset of GitHub's release JSON the update check reads.
public struct GitHubRelease: Decodable, Equatable, Sendable {
    public struct Asset: Decodable, Equatable, Sendable {
        public let name: String
        public let browserDownloadURL: String
        public let size: Int64

        enum CodingKeys: String, CodingKey {
            case name
            case browserDownloadURL = "browser_download_url"
            case size
        }
    }

    public let tagName: String
    public let htmlURL: String
    public let draft: Bool
    public let prerelease: Bool
    public let assets: [Asset]

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case htmlURL = "html_url"
        case draft
        case prerelease
        case assets
    }
}

/// Decides whether a published release is worth offering, and which asset the
/// platform would install.
///
/// Split from the networking so the wire format and the asset matching can be
/// tested against a real captured payload without a network call. Mirrors
/// `UpdateChecker.select` on Android.
public enum ReleaseChecker {
    /// Where every platform looks for a newer build of itself.
    ///
    /// This reads the GitHub Releases API rather than a hand-maintained version
    /// file, deliberately: publishing a release is already the act that makes a
    /// build available, so there is no second thing to remember to bump and
    /// nothing that can drift out of step with the actual download. The
    /// tradeoff is the unauthenticated rate limit (60/hour per IP) — generous
    /// for a check that runs at most once a day per install, and a limit hit
    /// degrades to "no update found", never to a wrong answer.
    public static let latestReleaseURL = URL(
        string: "https://api.github.com/repos/jobayersajal1/project-safe-world/releases/latest"
    )!

    public static let releasesPageURL = URL(
        string: "https://github.com/jobayersajal1/project-safe-world/releases/latest"
    )!

    /// Minimum time between automatic checks, so reopening Settings doesn't spend the rate limit.
    public static let checkIntervalHours: Double = 24

    public static func parse(_ data: Data) throws -> GitHubRelease {
        try JSONDecoder().decode(GitHubRelease.self, from: data)
    }

    /// - Parameter assetExtension: the file extension this platform can install
    ///   (".dmg" on macOS). Pass nil where nothing is installable — iOS — and
    ///   the result carries only `pageURL`.
    public static func select(
        release: GitHubRelease,
        currentVersion: String,
        assetExtension: String?
    ) -> AvailableUpdate? {
        // A draft isn't public and a pre-release isn't being offered to
        // everyone; neither should be pushed at users automatically.
        guard !release.draft, !release.prerelease else { return nil }

        var published = release.tagName.trimmingCharacters(in: .whitespacesAndNewlines)
        if published.hasPrefix("v") || published.hasPrefix("V") { published.removeFirst() }
        guard !published.isEmpty else { return nil }
        guard Version.isNewer(published, than: currentVersion) else { return nil }

        // The same release carries the .apk, .exe, and Chrome .zip too, so this
        // must pick out this platform's asset rather than taking the first.
        var asset: GitHubRelease.Asset?
        if let assetExtension {
            asset = release.assets.first {
                $0.name.lowercased().hasSuffix(assetExtension.lowercased())
            }
        }

        return AvailableUpdate(
            version: published,
            assetURL: asset.flatMap { URL(string: $0.browserDownloadURL) },
            sizeBytes: asset?.size ?? 0,
            pageURL: URL(string: release.htmlURL) ?? releasesPageURL
        )
    }
}
