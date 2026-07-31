import XCTest
@testable import SafeWorldCore

/// The catalogue's failure mode is silence: a bundle identifier that matches nothing installed
/// blocks nothing, and looks identical to the feature working. Nothing here can prove an identifier
/// is *correct* — only a device can — so these check the properties that would make one impossible.
///
/// Mirrors `AppGroupsTest.kt` on Android, including the messaging exclusion.
final class AppGroupsTests: XCTestCase {

    func testNoBundleIdAppearsTwice() {
        let ids = AppGroups.all.map(\.bundleId)
        let duplicates = Set(ids.filter { id in ids.filter { $0 == id }.count > 1 })
        XCTAssertEqual(duplicates, [], "duplicate bundle identifiers")
    }

    func testEveryBundleIdIsSyntacticallyValid() {
        // Reverse-DNS labels. Anything else cannot be an installed app's signing identifier, so it
        // is a typo.
        let pattern = try! NSRegularExpression(pattern: "^[A-Za-z][A-Za-z0-9-]*(\\.[A-Za-z0-9-]+)*$")
        let malformed = AppGroups.all.map(\.bundleId).filter { id in
            let range = NSRange(id.startIndex..., in: id)
            return pattern.firstMatch(in: id, range: range) == nil
        }
        XCTAssertEqual(malformed, [], "malformed bundle identifiers")
    }

    func testEveryGroupHasEntries() {
        for group in AppGroups.Group.allCases {
            XCTAssertFalse(AppGroups.forGroup(group).isEmpty, "\(group) has no apps")
        }
    }

    func testGroupIdsRoundTrip() {
        for group in AppGroups.Group.allCases {
            XCTAssertEqual(group, AppGroups.Group(rawValue: group.rawValue))
        }
        XCTAssertNil(AppGroups.Group(rawValue: "apps_nonexistent"))
    }

    /// Blocking someone's messaging is not what "block social media" means, and doing it silently
    /// would be the app deciding something the user did not ask for. Pinned so a later addition has
    /// to argue with a test rather than slip through.
    func testMessagingAppsAreNeverInTheCatalogue() {
        let messaging: Set<String> = [
            "net.whatsapp.WhatsApp",
            "com.facebook.Messenger",
            "ph.telegra.Telegraph",
            "org.whispersystems.signal",
            "com.viber",
            "com.skype.skype",
        ]
        let found = AppGroups.all.map(\.bundleId).filter { messaging.contains($0) }
        XCTAssertEqual(found, [], "messaging must stay reachable")
    }

    /// Each group pairs with a category of the same subject for the UI's benefit, but **the
    /// category does not switch it on** — see the note on `AppGroup.category`.
    func testEveryGroupNamesItsPairedCategory() {
        XCTAssertEqual(AppGroups.Group.social.category, .social)
        XCTAssertEqual(AppGroups.Group.entertainment.category, .entertainment)
        XCTAssertEqual(AppGroups.Group.games.category, .games)
    }

    // MARK: Which bundle ids a given set of switches produces

    func testNothingIsBlockedWhenNoGroupIsOn() {
        XCTAssertEqual(AppGroups.blockedBundleIds(enabledGroups: [], userPicked: []), [])
    }

    func testTurningOnAGroupBlocksItsAppsAndNothingElse() {
        let blocked = AppGroups.blockedBundleIds(enabledGroups: [.social], userPicked: [])
        XCTAssertTrue(blocked.contains("com.burbn.instagram"))
        XCTAssertTrue(blocked.contains("com.cardify.tinder"))
        XCTAssertFalse(blocked.contains("com.google.ios.youtube"))
        XCTAssertFalse(blocked.contains("com.dts.freefireth"))
    }

    func testGroupsCombine() {
        let blocked = AppGroups.blockedBundleIds(
            enabledGroups: [.entertainment, .games],
            userPicked: []
        )
        XCTAssertTrue(blocked.contains("com.google.ios.youtube"))
        XCTAssertTrue(blocked.contains("com.dts.freefireth"))
        XCTAssertFalse(blocked.contains("com.burbn.instagram"))
    }

    /// A hand-picked app was chosen individually, so there is no group to un-choose it. Turning a
    /// group off must not quietly un-block something the user added themselves.
    func testHandPickedIdsSurviveEveryGroupBeingOff() {
        let blocked = AppGroups.blockedBundleIds(
            enabledGroups: [],
            userPicked: ["com.example.somegame"]
        )
        XCTAssertEqual(blocked, ["com.example.somegame"])
    }

    func testAHandPickedAppAlreadyInAGroupIsNotDuplicated() {
        let blocked = AppGroups.blockedBundleIds(
            enabledGroups: [.games],
            userPicked: ["com.dts.freefireth"]
        )
        XCTAssertEqual(blocked.filter { $0 == "com.dts.freefireth" }.count, 1)
    }
}
