import XCTest
@testable import SafeWorldCore

/// `FilterEngine` restates `Matcher.decide`'s precedence over filters, because
/// `decide` takes `[CategoryId: Set<String>]` and 4.5M domains will not fit in
/// a Network Extension that way.
///
/// Restating rules is how two specs quietly diverge, so these tests drive the
/// same inputs through both and assert they agree. `decide` remains the spec;
/// this proves the tunnel obeys it.
final class FilterEngineTests: XCTestCase {

    /// The generator's vector, over the same five domains as `FuseFilterTests`.
    private let vector =
        "U1dGMQIDAAByayudQ4udTQAAAAUAAAABAAAAGAAAAAAAAAAA3DngrQAAAADitA3cAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAQi1kAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXZa79wAAAACjFf1+AAAAAA=="

    private let listed = ["bet365.com", "pornhub.com", "facebook.com", "example.org", "a.b.c.example.net"]

    /// Writes the same filter as every category, so `decide` and the engine see
    /// identical membership and any disagreement is precedence, not data.
    private func makeEngine(settings: Settings) throws -> (FilterEngine, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("engine-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let bytes = Data(base64Encoded: vector)!
        for id in CategoryId.allCases {
            try bytes.write(to: dir.appendingPathComponent("\(id.rawValue).filter"))
        }
        return (try FilterEngine(directory: dir, settings: settings), dir)
    }

    private func equivalentLists() -> [CategoryId: Set<String>] {
        var out: [CategoryId: Set<String>] = [:]
        for id in CategoryId.allCases { out[id] = Set(listed) }
        return out
    }

    /// Every case where the two must return the same answer.
    private func assertAgreement(
        _ settings: Settings,
        _ hosts: [String],
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let (engine, dir) = try makeEngine(settings: settings)
        defer { try? FileManager.default.removeItem(at: dir) }
        let lists = equivalentLists()

        for host in hosts {
            let viaFilter = engine.isBlocked(host)
            let viaDecide = Matcher.decide(host: host, settings: settings, blocklists: lists).blocked
            XCTAssertEqual(
                viaFilter, viaDecide,
                "disagreement on \(host): filter=\(viaFilter) decide=\(viaDecide)",
                file: file, line: line
            )
        }
    }

    private let hosts = [
        "bet365.com", "www.bet365.com", "sports.live.bet365.com",
        "pornhub.com", "facebook.com", "example.org",
        "a.b.c.example.net", "c.example.net",
        "wikipedia.org", "github.com", "", "not a host",
    ]

    func testAgreesWithDecideOnDefaults() throws {
        try assertAgreement(.defaults(), hosts)
    }

    func testAgreesWhenTheMasterSwitchIsOff() throws {
        var s = Settings.defaults()
        s.enabled = false
        try assertAgreement(s, hosts)
    }

    func testAgreesWhenACategoryIsDisabled() throws {
        var s = Settings.defaults()
        for id in CategoryId.allCases { s.categories[id] = false }
        try assertAgreement(s, hosts)
    }

    func testCustomAllowBeatsTheLists() throws {
        var s = Settings.defaults()
        s.customAllow = ["bet365.com"]
        try assertAgreement(s, hosts)

        let (engine, dir) = try makeEngine(settings: s)
        defer { try? FileManager.default.removeItem(at: dir) }
        // The escape hatch a false positive relies on: it must win outright.
        XCTAssertFalse(engine.isBlocked("bet365.com"))
        XCTAssertFalse(engine.isBlocked("www.bet365.com"))
    }

    func testCustomBlockCatchesWhatNoListHas() throws {
        var s = Settings.defaults()
        s.customBlock = ["intranet.example"]
        try assertAgreement(s, hosts + ["intranet.example", "a.intranet.example"])

        let (engine, dir) = try makeEngine(settings: s)
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertTrue(engine.isBlocked("intranet.example"))
    }

    func testCountsOnlyEnabledCategories() throws {
        var s = Settings.defaults()
        let (all, dirA) = try makeEngine(settings: s)
        defer { try? FileManager.default.removeItem(at: dirA) }
        let enabledCount = Categories.all.filter { s.categories[$0.id] == true }.count
        XCTAssertEqual(all.blockedDomainCount, listed.count * enabledCount)

        for id in CategoryId.allCases { s.categories[id] = false }
        let (none, dirB) = try makeEngine(settings: s)
        defer { try? FileManager.default.removeItem(at: dirB) }
        XCTAssertEqual(none.blockedDomainCount, 0)
    }

    func testRefusesToStartWithNoFilters() throws {
        let empty = FileManager.default.temporaryDirectory
            .appendingPathComponent("empty-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: empty) }

        // An engine with no filters answers "not blocked" to everything, which
        // is indistinguishable from a healthy app. It must refuse instead.
        XCTAssertThrowsError(try FilterEngine(directory: empty, settings: .defaults()))
    }
}
