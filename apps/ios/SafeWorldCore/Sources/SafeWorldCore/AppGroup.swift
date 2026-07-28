import Foundation

/// The App Group shared between the SafeWorld app and the SafeWorldBlocker
/// Safari Content Blocker extension, used to hand off the generated rule
/// list. Single source of truth for the identifier and filename so the app
/// (writer) and extension (reader) can't drift apart.
public enum AppGroup {
    public static let identifier = "group.com.safeworld.app"
    public static let blockerListFilename = "blockerList.json"

    public static func containerURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    public static func blockerListURL() -> URL? {
        containerURL()?.appendingPathComponent(blockerListFilename)
    }

    /// Key the host app stores `Settings` under, shared so the tunnel extension
    /// reads exactly what the app wrote rather than keeping its own copy.
    public static let settingsKey = "settings"

    public static func sharedDefaults() -> UserDefaults {
        UserDefaults(suiteName: identifier) ?? .standard
    }

    /// The current settings as written by the app.
    ///
    /// Falls back to defaults rather than throwing: the tunnel must still make
    /// a decision on every query, and defaults block the three protection
    /// categories — the safe direction if the app has not written yet.
    public static func loadSharedSettings() -> Settings {
        guard
            let data = sharedDefaults().data(forKey: settingsKey),
            let stored = try? JSONDecoder().decode(Settings.self, from: data)
        else {
            return .defaults()
        }
        return stored
    }

    /// Where the tunnel reads its filters from. The app copies them out of its
    /// bundle on first run, because an extension cannot read the host app's
    /// bundle directly.
    public static func filterURL(for category: CategoryId) -> URL? {
        containerURL()?.appendingPathComponent("\(category.rawValue).filter")
    }
}
