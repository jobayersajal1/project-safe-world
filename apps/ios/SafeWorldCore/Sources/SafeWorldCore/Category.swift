import Foundation

/// Mirrors `packages/core/src/categories.ts`. Keep in sync with that file —
/// it is the source of truth for category ids and labels across platforms.
/// Raw values are deliberately opaque — they are the keys in the published
/// payload and the bundled resource file names, both of which are visible in an
/// unpacked app, and `"adult"` would announce a list's contents even though its
/// domains are encoded. The case names stay meaningful so the code reads
/// normally, and `CategoryMeta.label` is what the UI shows.
public enum CategoryId: String, CaseIterable, Codable, Hashable, Sendable {
    case scam = "list1"
    case gambling = "list2"
    case adult = "list3"
    case social = "list4"
    case entertainment = "list5"
}

public struct CategoryMeta: Sendable {
    public let id: CategoryId
    /// Short human label for UI.
    public let label: String
    /// One-line description shown in settings.
    public let description: String
    /// Whether the category is enabled by default on first install.
    public let defaultEnabled: Bool
    /// Whether the user may turn this category off. The three protection
    /// categories are the reason the app exists; the opt-in ones are lifestyle
    /// choices, off until asked for. See `categories.ts` for the full note.
    public let optional: Bool

    public init(
        id: CategoryId,
        label: String,
        description: String,
        defaultEnabled: Bool,
        optional: Bool
    ) {
        self.id = id
        self.label = label
        self.description = description
        self.defaultEnabled = defaultEnabled
        self.optional = optional
    }
}

public enum Categories {
    public static let all: [CategoryMeta] = [
        CategoryMeta(
            id: .scam,
            label: "Scam & Malware",
            description: "Phishing, fraud, and malware-hosting sites.",
            defaultEnabled: true,
            optional: false
        ),
        CategoryMeta(
            id: .gambling,
            label: "Gambling",
            description: "Casinos, betting, and gambling sites.",
            defaultEnabled: true,
            optional: false
        ),
        CategoryMeta(
            id: .adult,
            label: "Adult Content",
            description: "Pornography and explicit adult sites.",
            defaultEnabled: true,
            optional: false
        ),
        CategoryMeta(
            id: .social,
            label: "Social Media",
            description: "Facebook, Instagram, X/Twitter, TikTok, and similar feeds.",
            defaultEnabled: false,
            optional: true
        ),
        CategoryMeta(
            id: .entertainment,
            label: "Entertainment",
            description: "YouTube, Netflix, and other streaming and video sites.",
            defaultEnabled: false,
            optional: true
        ),
    ]

    /// Categories the user opts into, presented as a question rather than as
    /// part of the app's baseline promise.
    public static let optional: [CategoryMeta] = all.filter(\.optional)

    public static func meta(for id: CategoryId) -> CategoryMeta {
        // `all` always has one entry per CategoryId case, so this cannot fail.
        all.first { $0.id == id }!
    }
}
