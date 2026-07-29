import XCTest
@testable import SafeWorldCore

/// Parsing and asset selection, against the real shape of a GitHub release
/// response. Mirrors `UpdateCheckerTest.kt` on Android.
///
/// The payload is trimmed from an actual reply for the v0.1.0 release —
/// abbreviated, but with its unknown fields left in on purpose, because the one
/// thing that would silently break this is GitHub adding a field and the
/// decoder refusing the whole document over it.
final class ReleaseCheckerTests: XCTestCase {

    private func release(_ json: String = latestReleaseJSON) throws -> GitHubRelease {
        try ReleaseChecker.parse(Data(json.utf8))
    }

    func testParsesARealReleasePayloadIgnoringFieldsItDoesNotKnow() throws {
        let r = try release()
        XCTAssertEqual(r.tagName, "v0.1.0")
        XCTAssertEqual(r.assets.count, 4)
    }

    func testPicksTheDmgOutOfAReleaseThatAlsoCarriesApkExeAndZip() throws {
        let update = ReleaseChecker.select(
            release: try release(), currentVersion: "0.0.9", assetExtension: ".dmg"
        )
        XCTAssertEqual(update?.assetURL?.lastPathComponent, "SafeWorld-0.1.0-macOS.dmg")
        XCTAssertEqual(update?.sizeBytes, 2_523_649)
    }

    func testStripsTheLeadingVSoTheTagComparesAgainstABundleVersion() throws {
        let update = ReleaseChecker.select(
            release: try release(), currentVersion: "0.0.9", assetExtension: ".dmg"
        )
        XCTAssertEqual(update?.version, "0.1.0")
    }

    func testTheReleaseTheUserIsAlreadyOnIsNotAnUpdate() throws {
        XCTAssertNil(ReleaseChecker.select(
            release: try release(), currentVersion: "0.1.0", assetExtension: ".dmg"
        ))
    }

    func testANewerInstallIsNotOfferedAnOlderRelease() throws {
        XCTAssertNil(ReleaseChecker.select(
            release: try release(), currentVersion: "0.2.0", assetExtension: ".dmg"
        ))
    }

    func testDraftsAndPreReleasesAreNotOffered() throws {
        let draft = try release(Self.latestReleaseJSON.replacingOccurrences(
            of: "\"draft\": false", with: "\"draft\": true"
        ))
        XCTAssertNil(ReleaseChecker.select(
            release: draft, currentVersion: "0.0.9", assetExtension: ".dmg"
        ))

        let pre = try release(Self.latestReleaseJSON.replacingOccurrences(
            of: "\"prerelease\": false", with: "\"prerelease\": true"
        ))
        XCTAssertNil(ReleaseChecker.select(
            release: pre, currentVersion: "0.0.9", assetExtension: ".dmg"
        ))
    }

    /// iOS passes no asset extension because the OS cannot install one. It must
    /// still learn that an update exists, and still get somewhere to send the
    /// user — otherwise the whole check is pointless there.
    func testNoAssetExtensionStillReportsTheUpdateAndItsPage() throws {
        let update = ReleaseChecker.select(
            release: try release(), currentVersion: "0.0.9", assetExtension: nil
        )
        XCTAssertEqual(update?.version, "0.1.0")
        XCTAssertNil(update?.assetURL)
        XCTAssertEqual(
            update?.pageURL.absoluteString,
            "https://github.com/jobayersajal1/project-safe-world/releases/tag/v0.1.0"
        )
    }

    func testAReleaseWithNoMatchingAssetStillOffersItsPage() throws {
        let noDmg = try release(Self.latestReleaseJSON.replacingOccurrences(
            of: "SafeWorld-0.1.0-macOS.dmg", with: "SafeWorld-0.1.0-macOS.txt"
        ))
        let update = ReleaseChecker.select(
            release: noDmg, currentVersion: "0.0.9", assetExtension: ".dmg"
        )
        XCTAssertNotNil(update)
        XCTAssertNil(update?.assetURL)
    }

    private static let latestReleaseJSON = """
    {
      "url": "https://api.github.com/repos/jobayersajal1/project-safe-world/releases/258",
      "id": 258,
      "html_url": "https://github.com/jobayersajal1/project-safe-world/releases/tag/v0.1.0",
      "tag_name": "v0.1.0",
      "target_commitish": "main",
      "name": "Safe World 0.1.0 — Android, macOS, Windows",
      "draft": false,
      "prerelease": false,
      "created_at": "2026-07-27T01:05:44Z",
      "published_at": "2026-07-27T01:30:42Z",
      "assets": [
        {
          "name": "SafeWorld-0.1.0-macOS.dmg",
          "content_type": "application/x-apple-diskimage",
          "state": "uploaded",
          "size": 2523649,
          "download_count": 3,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0-macOS.dmg"
        },
        {
          "name": "SafeWorld-0.1.0-win-x64.exe",
          "content_type": "application/x-msdownload",
          "state": "uploaded",
          "size": 144246169,
          "download_count": 1,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0-win-x64.exe"
        },
        {
          "name": "SafeWorld-0.1.0.apk",
          "content_type": "application/vnd.android.package-archive",
          "state": "uploaded",
          "size": 20243764,
          "download_count": 12,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0.apk"
        },
        {
          "name": "SafeWorld-chrome-0.1.0.zip",
          "content_type": "application/zip",
          "state": "uploaded",
          "size": 11699461,
          "download_count": 0,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-chrome-0.1.0.zip"
        }
      ],
      "body": "First Android, macOS, and Windows releases."
    }
    """

    private var latestReleaseJSON: String { Self.latestReleaseJSON }
}
