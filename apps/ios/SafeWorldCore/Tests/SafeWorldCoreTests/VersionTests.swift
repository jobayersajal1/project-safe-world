import XCTest
@testable import SafeWorldCore

/// These cases are duplicated in the Kotlin port
/// (apps/android/core/src/test/.../VersionTest.kt). They are the contract
/// between the two update checkers: if one side orders a pair differently, that
/// platform either nags about an update that isn't one or stays silent about
/// one that is.
final class VersionTests: XCTestCase {
    func testOrdersByNumericComponents() {
        XCTAssertTrue(Version.isNewer("0.2.0", than: "0.1.0"))
        XCTAssertTrue(Version.isNewer("1.0.0", than: "0.9.9"))
        XCTAssertTrue(Version.isNewer("0.1.1", than: "0.1.0"))
        XCTAssertFalse(Version.isNewer("0.1.0", than: "0.2.0"))
    }

    func testTheSameVersionIsNotAnUpdate() {
        XCTAssertFalse(Version.isNewer("0.1.0", than: "0.1.0"))
    }

    func testIgnoresALeadingVSoAGitTagComparesAgainstABundleVersion() {
        XCTAssertFalse(Version.isNewer("v0.1.0", than: "0.1.0"))
        XCTAssertTrue(Version.isNewer("v0.2.0", than: "0.1.0"))
    }

    func testTreatsMissingTrailingComponentsAsZero() {
        XCTAssertFalse(Version.isNewer("1.2", than: "1.2.0"))
        XCTAssertTrue(Version.isNewer("1.3", than: "1.2.9"))
    }

    func testComparesComponentsNumericallyNotAsText() {
        // The bug this pins: "10" sorts before "9" as a string.
        XCTAssertTrue(Version.isNewer("0.10.0", than: "0.9.0"))
        XCTAssertTrue(Version.isNewer("0.1.10", than: "0.1.9"))
    }

    func testAPreReleasePrecedesTheReleaseItLeadsUpTo() {
        XCTAssertTrue(Version.isNewer("1.0.0", than: "1.0.0-beta"))
        XCTAssertFalse(Version.isNewer("1.0.0-beta", than: "1.0.0"))
        // Still newer than the previous full release.
        XCTAssertTrue(Version.isNewer("1.0.0-beta", than: "0.9.0"))
    }

    func testBuildMetadataDoesNotAffectPrecedence() {
        XCTAssertFalse(Version.isNewer("1.0.0+build9", than: "1.0.0"))
        XCTAssertFalse(Version.isNewer("1.0.0", than: "1.0.0+build9"))
    }

    func testMalformedInputReadsAsZeroRatherThanTrapping() {
        // A garbage tag must not crash the update check, only fail to look new.
        XCTAssertFalse(Version.isNewer("", than: "0.1.0"))
        XCTAssertFalse(Version.isNewer("not-a-version", than: "0.1.0"))
        XCTAssertTrue(Version.isNewer("0.1.0", than: "garbage"))
    }
}
