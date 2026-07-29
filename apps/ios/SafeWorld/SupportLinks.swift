import Foundation

/// Where a stuck user can reach a human.
///
/// Port of `ui/SupportLinks.kt`, and kept in Swift rather than in
/// `Localizable.xcstrings` for the same reason: a phone number, an email
/// address, and a repository URL are the same in every language, and putting
/// them in the translatable resources invites a translator to "localize" one.
enum SupportLinks {
    static let whatsAppNumber = "+84963885"

    static let email = "safeworldwebsiteblock@gmail.com"

    static let github = "https://github.com/jobayersajal1/project-safe-world"

    /// The Facebook group doesn't exist yet. Held as nil rather than as an empty
    /// string so the UI has to decide what to show, instead of silently opening
    /// a link to nowhere.
    static let facebookGroup: String? = nil

    /// `wa.me` wants digits only — no `+`, spaces, or dashes.
    static var whatsAppURL: URL? {
        URL(string: "https://wa.me/" + whatsAppNumber.filter(\.isNumber))
    }

    static var emailURL: URL? { URL(string: "mailto:\(email)") }

    static var githubURL: URL? { URL(string: github) }
}
