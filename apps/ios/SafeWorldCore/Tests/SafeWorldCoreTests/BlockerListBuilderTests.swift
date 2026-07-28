import XCTest
@testable import SafeWorldCore

final class BlockerListBuilderTests: XCTestCase {
    func testDisabledSettingsProduceNoRules() {
        var s = Settings.defaults()
        s.enabled = false
        XCTAssertEqual(BlockerListBuilder.build(settings: s, blocklists: [.gambling: ["bet365.com"]]), [])
    }

    func testEmitsABlockRulePerEnabledCategoryWithDomains() {
        let s = Settings.defaults()
        let rules = BlockerListBuilder.build(settings: s, blocklists: [
            .scam: [],
            .gambling: ["bet365.com"],
            .adult: ["pornhub.com"],
        ])
        // scam has no domains, so only gambling + adult contribute block rules.
        XCTAssertEqual(rules.count, 2)
        XCTAssertEqual(rules[0].action.type, "block")
        XCTAssertEqual(rules[0].trigger.ifDomain, ["*bet365.com"])
        XCTAssertEqual(rules[1].trigger.ifDomain, ["*pornhub.com"])
    }

    func testCustomAllowRuleIsLastAndIgnoresPreviousRules() {
        var s = Settings.defaults()
        s.customAllow = ["bet365.com"]
        let rules = BlockerListBuilder.build(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertEqual(rules.last?.action.type, "ignore-previous-rules")
        XCTAssertEqual(rules.last?.trigger.ifDomain, ["*bet365.com"])
    }

    func testDisabledCategoryContributesNoRule() {
        var s = Settings.defaults()
        s.categories[.gambling] = false
        let rules = BlockerListBuilder.build(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertTrue(rules.isEmpty)
    }
}

final class BlocklistsTests: XCTestCase {
    func testBundledDomainsLoadForEveryCategory() {
        for id in CategoryId.allCases {
            XCTAssertFalse(Blocklists.domains(for: id).isEmpty, "\(id) should have bundled domains")
        }
    }

    /// The bundled resources are scrambled, so "not empty" is not enough — a
    /// decode that silently failed would still return 20,000 entries, just
    /// base64 ones that match nothing. This checks they came back as domains.
    func testBundledDomainsAreUnscrambled() {
        for id in CategoryId.allCases {
            let domains = Blocklists.domains(for: id)
            let sample = domains.prefix(200)
            XCTAssertTrue(
                sample.allSatisfy { $0.contains(".") && $0 == $0.lowercased() },
                "\(id) looks like it was not unscrambled: \(sample.prefix(3))"
            )
        }
        XCTAssertTrue(
            Blocklists.domains(for: .gambling).contains("bet365.com"),
            "a known curated domain should survive the scramble round-trip"
        )
    }
}
