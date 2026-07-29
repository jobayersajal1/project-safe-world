import XCTest
@testable import SafeWorldCore

/// The PIN gate's whole rule, tested without a simulator.
///
/// This is the logic a wrong answer in is expensive both ways: a false negative
/// lets someone unblock gambling with one tap in a weak moment, which is the
/// failure the PIN exists to prevent; a false positive demands a PIN to make
/// protection *stronger*, which trains people to resent it.
final class ProtectionChangeTests: XCTestCase {

    private func settings(
        enabled: Bool = true,
        categories: [CategoryId: Bool] = [.scam: true, .gambling: true, .adult: true,
                                          .social: false, .entertainment: false],
        customAllow: [String] = [],
        customBlock: [String] = []
    ) -> Settings {
        var s = Settings.defaults()
        s.enabled = enabled
        s.categories = categories
        s.customAllow = customAllow
        s.customBlock = customBlock
        return s
    }

    func testNoChangeIsNotWeakening() {
        let s = settings()
        XCTAssertFalse(ProtectionChange.weakens(from: s, to: s))
    }

    // MARK: Master switch

    func testTurningProtectionOffWeakens() {
        XCTAssertTrue(ProtectionChange.weakens(
            from: settings(enabled: true),
            to: settings(enabled: false)
        ))
    }

    func testTurningProtectionOnDoesNot() {
        XCTAssertFalse(ProtectionChange.weakens(
            from: settings(enabled: false),
            to: settings(enabled: true)
        ))
    }

    // MARK: Categories

    func testTurningACategoryOffWeakens() {
        var on = settings()
        on.categories[.social] = true
        var off = on
        off.categories[.social] = false

        XCTAssertTrue(ProtectionChange.weakens(from: on, to: off))
    }

    func testTurningACategoryOnDoesNot() {
        var off = settings()
        off.categories[.social] = false
        var on = off
        on.categories[.social] = true

        XCTAssertFalse(ProtectionChange.weakens(from: off, to: on))
    }

    /// A category dropping out of the dictionary entirely reads as "not enabled"
    /// to `decide`, so it has to read that way here too.
    func testRemovingACategoryKeyWeakens() {
        var on = settings()
        on.categories[.social] = true
        var removed = on
        removed.categories[.social] = nil

        XCTAssertTrue(ProtectionChange.weakens(from: on, to: removed))
    }

    // MARK: Custom block list

    func testAddingABlockedSiteDoesNot() {
        XCTAssertFalse(ProtectionChange.weakens(
            from: settings(customBlock: ["a.com"]),
            to: settings(customBlock: ["a.com", "b.com"])
        ))
    }

    func testRemovingABlockedSiteWeakens() {
        XCTAssertTrue(ProtectionChange.weakens(
            from: settings(customBlock: ["a.com", "b.com"]),
            to: settings(customBlock: ["a.com"])
        ))
    }

    /// The case a count comparison gets wrong: same number of entries, but one
    /// of them is no longer blocked.
    func testSwappingOneBlockedSiteForAnotherWeakens() {
        XCTAssertTrue(ProtectionChange.weakens(
            from: settings(customBlock: ["a.com", "b.com"]),
            to: settings(customBlock: ["a.com", "c.com"])
        ))
    }

    func testReorderingTheBlockListIsNotAChange() {
        XCTAssertFalse(ProtectionChange.weakens(
            from: settings(customBlock: ["a.com", "b.com"]),
            to: settings(customBlock: ["b.com", "a.com"])
        ))
    }

    // MARK: Custom allow list

    /// Allow wins over every category in `decide`, so adding here is the
    /// weakening direction — the opposite of the block list, and the case a
    /// generic "did anything get removed?" check would miss completely.
    func testAddingAnAllowedSiteWeakens() {
        XCTAssertTrue(ProtectionChange.weakens(
            from: settings(customAllow: []),
            to: settings(customAllow: ["a.com"])
        ))
    }

    func testRemovingAnAllowedSiteDoesNot() {
        XCTAssertFalse(ProtectionChange.weakens(
            from: settings(customAllow: ["a.com"]),
            to: settings(customAllow: [])
        ))
    }

    func testSwappingOneAllowedSiteForAnotherWeakens() {
        XCTAssertTrue(ProtectionChange.weakens(
            from: settings(customAllow: ["a.com"]),
            to: settings(customAllow: ["b.com"])
        ))
    }

    // MARK: Combinations

    /// Strengthening one thing must not buy the right to weaken another in the
    /// same save.
    func testStrengtheningDoesNotOffsetWeakening() {
        var before = settings(customBlock: ["a.com"])
        before.categories[.social] = true

        var after = before
        after.customBlock = ["a.com", "b.com"]   // stronger
        after.categories[.social] = false          // weaker

        XCTAssertTrue(ProtectionChange.weakens(from: before, to: after))
    }

    func testTheRuleAgreesWithDecideOnWhatItClaims() {
        // A domain that decide() blocks before the change and allows after it is
        // exactly what "weakens" is supposed to mean.
        let before = settings(customBlock: ["gamble.example"])
        let after = settings(customAllow: ["gamble.example"], customBlock: [])
        let lists: [CategoryId: Set<String>] = [:]

        XCTAssertTrue(Matcher.decide(host: "gamble.example", settings: before, blocklists: lists).blocked)
        XCTAssertFalse(Matcher.decide(host: "gamble.example", settings: after, blocklists: lists).blocked)
        XCTAssertTrue(ProtectionChange.weakens(from: before, to: after))
    }
}
