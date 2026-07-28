import XCTest
@testable import SafeWorldCore

final class HostsFileBuilderTests: XCTestCase {
    func testDisabledSettingsProduceNoHosts() {
        var s = Settings.defaults()
        s.enabled = false
        let hosts = HostsFileBuilder.blockedHosts(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertTrue(hosts.isEmpty)
    }

    func testEmitsBareAndWwwVariantsForEnabledCategoryDomains() {
        let s = Settings.defaults()
        let hosts = HostsFileBuilder.blockedHosts(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertEqual(hosts, ["bet365.com", "www.bet365.com"])
    }

    func testCustomAllowOverridesCategoryBlock() {
        var s = Settings.defaults()
        s.customAllow = ["bet365.com"]
        let hosts = HostsFileBuilder.blockedHosts(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertTrue(hosts.isEmpty)
    }

    func testCustomBlockIsIncluded() {
        var s = Settings.defaults()
        s.customBlock = ["evil.example"]
        let hosts = HostsFileBuilder.blockedHosts(settings: s, blocklists: [:])
        XCTAssertEqual(hosts, ["evil.example", "www.evil.example"])
    }

    func testDisabledCategoryContributesNoHosts() {
        var s = Settings.defaults()
        s.categories[.gambling] = false
        let hosts = HostsFileBuilder.blockedHosts(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertTrue(hosts.isEmpty)
    }

    func testRenderBlockIsMarkerDelimitedAndSorted() {
        let s = Settings.defaults()
        let block = HostsFileBuilder.renderBlock(settings: s, blocklists: [.gambling: ["bet365.com"]])
        XCTAssertTrue(block.hasPrefix("# BEGIN SAFEWORLD\n"))
        XCTAssertTrue(block.hasSuffix("# END SAFEWORLD\n"))
        XCTAssertTrue(block.contains("0.0.0.0 bet365.com"))
        XCTAssertTrue(block.contains("0.0.0.0 www.bet365.com"))
    }

    func testApplyAppendsBlockWhenNoMarkersPresent() {
        let existing = "127.0.0.1 localhost\n"
        let block = "# BEGIN SAFEWORLD\n0.0.0.0 bet365.com\n# END SAFEWORLD\n"
        let result = HostsFileBuilder.apply(managedBlock: block, toExistingContents: existing)
        XCTAssertEqual(result, existing + block)
    }

    func testApplyReplacesExistingManagedBlockInPlace() {
        let existing = """
        127.0.0.1 localhost
        # BEGIN SAFEWORLD
        0.0.0.0 old.example
        # END SAFEWORLD
        255.255.255.255 broadcasthost
        """ + "\n"
        let block = "# BEGIN SAFEWORLD\n0.0.0.0 new.example\n# END SAFEWORLD\n"
        let result = HostsFileBuilder.apply(managedBlock: block, toExistingContents: existing)
        XCTAssertEqual(result, """
        127.0.0.1 localhost
        # BEGIN SAFEWORLD
        0.0.0.0 new.example
        # END SAFEWORLD
        255.255.255.255 broadcasthost
        """ + "\n")
    }
}
