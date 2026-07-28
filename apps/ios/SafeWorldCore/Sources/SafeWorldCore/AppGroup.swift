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
}
