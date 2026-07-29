import XCTest
@testable import SafeWorldCore

/// What `Settings` actually looks like on disk.
///
/// The macOS daemon reads the file the app writes, so the two must agree. This pins the shape so
/// a hand-written or hand-edited settings file can be checked against reality rather than guessed
/// at — the daemon falls back to defaults on a decode failure, which means a mismatch shows up as
/// "protection stays on no matter what the app says", not as an error.
final class SettingsWireFormatTests: XCTestCase {
    func testRoundTripsThroughJson() throws {
        var settings = Settings.defaults()
        settings.enabled = false
        settings.categories[.social] = true

        let data = try JSONEncoder().encode(settings)
        let json = String(data: data, encoding: .utf8)!
        print("ENCODED SETTINGS: \(json)")

        let decoded = try JSONDecoder().decode(Settings.self, from: data)
        XCTAssertEqual(decoded.enabled, false)
        XCTAssertEqual(decoded.categories[.social], true)
        XCTAssertEqual(decoded.categories[.scam], settings.categories[.scam])
    }
}
