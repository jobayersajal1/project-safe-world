import XCTest
@testable import SafeWorldCore

/// Mirrors packages/core/test/matcher.test.ts — keep the two in sync.
final class MatcherTests: XCTestCase {
    private func lists(
        scam: [String] = [],
        gambling: [String] = ["bet365.com"],
        adult: [String] = ["pornhub.com"]
    ) -> [CategoryId: Set<String>] {
        [.scam: Set(scam), .gambling: Set(gambling), .adult: Set(adult)]
    }

    // MARK: normalizeHost

    func testNormalizeHostStripsSchemePathPortWwwAndLowercases() {
        XCTAssertEqual(Matcher.normalizeHost("HTTPS://WWW.Bet365.com:443/path?x=1"), "bet365.com")
    }

    func testNormalizeHostStripsUserinfoAndTrailingDot() {
        XCTAssertEqual(Matcher.normalizeHost("http://user:pass@sub.example.com./a"), "sub.example.com")
    }

    func testNormalizeHostReturnsEmptyForBlankInput() {
        XCTAssertEqual(Matcher.normalizeHost("   "), "")
    }

    // MARK: hostMatchesDomain

    func testHostMatchesDomainMatchesExactAndSubdomainsButNotSiblings() {
        XCTAssertTrue(Matcher.hostMatchesDomain("bet365.com", "bet365.com"))
        XCTAssertTrue(Matcher.hostMatchesDomain("m.bet365.com", "bet365.com"))
        XCTAssertFalse(Matcher.hostMatchesDomain("notbet365.com", "bet365.com"))
    }

    // MARK: decide

    func testDecideBlocksAListedDomainInAnEnabledCategory() {
        let d = Matcher.decide(host: "www.bet365.com", settings: .defaults(), blocklists: lists())
        XCTAssertEqual(d, Matcher.BlockDecision(blocked: true, reason: "list2"))
    }

    func testDecideBlocksSubdomainsOfAListedDomain() {
        XCTAssertTrue(Matcher.decide(host: "sports.bet365.com", settings: .defaults(), blocklists: lists()).blocked)
    }

    func testDecideDoesNotBlockWhenMasterSwitchIsOff() {
        var s = Settings.defaults()
        s.enabled = false
        XCTAssertFalse(Matcher.decide(host: "bet365.com", settings: s, blocklists: lists()).blocked)
    }

    func testDecideDoesNotBlockWhenTheCategoryIsDisabled() {
        var s = Settings.defaults()
        s.categories[.gambling] = false
        XCTAssertFalse(Matcher.decide(host: "bet365.com", settings: s, blocklists: lists()).blocked)
    }

    func testDecideCustomAllowOverridesACategoryBlock() {
        var s = Settings.defaults()
        s.customAllow = ["bet365.com"]
        XCTAssertFalse(Matcher.decide(host: "bet365.com", settings: s, blocklists: lists()).blocked)
    }

    func testDecideCustomBlockBlocksAnOtherwiseAllowedDomain() {
        var s = Settings.defaults()
        s.customBlock = ["example.com"]
        XCTAssertEqual(
            Matcher.decide(host: "app.example.com", settings: s, blocklists: lists()),
            Matcher.BlockDecision(blocked: true, reason: "custom")
        )
    }

    func testDecideAllowsUnlistedDomains() {
        XCTAssertFalse(Matcher.decide(host: "wikipedia.org", settings: .defaults(), blocklists: lists()).blocked)
    }
}
