import Foundation
import SafariServices
import SafeWorldCore

/// Rebuilds the Safari Content Blocker rule list from the current settings
/// and hands it to Safari. This is the iOS analogue of `syncAll` in the
/// Chrome extension's background.ts, just against one combined content
/// blocker list instead of several toggleable declarativeNetRequest rulesets.
enum ContentBlockerSync {
    /// Must match SafeWorldBlocker's PRODUCT_BUNDLE_IDENTIFIER in project.yml.
    static let extensionIdentifier = "com.safeworld.app.blocker"

    static func sync(
        settings: Settings,
        remoteDomains: [CategoryId: [String]],
        completion: @escaping (String?) -> Void
    ) {
        let rules = BlockerListBuilder.build(settings: settings, remoteDomains: remoteDomains)
        do {
            let data = try BlockerListBuilder.encode(rules)
            guard let url = AppGroup.blockerListURL() else {
                completion("App Group container unavailable — check the app's entitlements.")
                return
            }
            try data.write(to: url, options: .atomic)
        } catch {
            completion(error.localizedDescription)
            return
        }

        SFContentBlockerManager.reloadContentBlocker(withIdentifier: extensionIdentifier) { error in
            DispatchQueue.main.async { completion(error?.localizedDescription) }
        }
    }
}
