import Foundation
import SwiftUI
import SafeWorldCore

/// The user's chosen UI language, and string lookup that honours it.
///
/// The iOS counterpart of `LocaleHelper.kt`. Android wraps the `Context`; here
/// the equivalent is resolving through a per-language `Bundle`.
///
/// **Why explicit lookup rather than plain `Text("key")`.** SwiftUI resolves a
/// `LocalizedStringKey` against `Bundle.main`, and `Bundle.main` picks its
/// language from the *system* preference, not from `\.environment(\.locale)`.
/// Setting the locale alone changes date and number formatting and nothing else,
/// so an in-app picker built that way appears to do nothing. Going through the
/// language's own bundle is what actually switches the words. The environment
/// locale is still set, in `RootView`, because that is what makes
/// `CountFormat` and every other formatter agree with the text around them.
enum Language {

    /// The languages offered in Settings. `""` means "follow the system", and is
    /// the default — a fresh install comes up in the phone's language with no
    /// configuration, same as Android.
    static let supported = ["", "en", "bn", "es", "ar"]

    private static let key = "language"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: AppGroup.identifier) ?? .standard
    }

    /// The stored tag, or `""` when the user is following the system.
    static var stored: String {
        get { defaults.string(forKey: key) ?? "" }
        set { defaults.set(newValue, forKey: key) }
    }

    /// The language actually in effect: the stored choice, or the best match
    /// between the system's preferences and what we ship.
    static var effectiveTag: String {
        let tag = stored
        if !tag.isEmpty { return tag }
        let available = supported.filter { !$0.isEmpty }
        let preferred = Bundle.preferredLocalizations(from: available, forPreferences: nil)
        return preferred.first ?? "en"
    }

    static var effectiveLocale: Locale { Locale(identifier: effectiveTag) }

    /// Arabic is the reason this exists. Layout direction does not follow from
    /// the locale on its own here, exactly as `LocaleHelper.withLocale` has to
    /// call `setLayoutDirection` explicitly on Android.
    static var layoutDirection: LayoutDirection {
        Locale.Language(identifier: effectiveTag).characterDirection == .rightToLeft
            ? .rightToLeft
            : .leftToRight
    }

    /// Display name of a language in *its own* language, so someone who has
    /// landed in a language they can't read can still find their way out.
    static func displayName(_ tag: String) -> String {
        if tag.isEmpty { return string("language_system") }
        let locale = Locale(identifier: tag)
        let name = locale.localizedString(forLanguageCode: tag) ?? tag
        return name.prefix(1).uppercased() + name.dropFirst()
    }

    // MARK: Lookup

    private static var cache: [String: Bundle] = [:]

    private static func bundle() -> Bundle {
        let tag = effectiveTag
        if let cached = cache[tag] { return cached }
        // Falls back to the main bundle rather than failing: a missing .lproj
        // should cost the translation, not the screen.
        let resolved = Bundle.main.path(forResource: tag, ofType: "lproj")
            .flatMap(Bundle.init(path:)) ?? .main
        cache[tag] = resolved
        return resolved
    }

    /// Clears the resolved bundle so the next lookup uses the new language.
    static func invalidate() { cache.removeAll() }

    static func string(_ key: String) -> String {
        NSLocalizedString(key, bundle: bundle(), comment: "")
    }

    static func string(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: effectiveLocale, arguments: arguments)
    }

    /// Plural-aware lookup. The catalog's `variations` compile to the same form
    /// a `.stringsdict` does, which only `localizedStringWithFormat` selects
    /// between — `String(format:)` would always produce the "other" case.
    static func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(string(key), count)
    }
}

/// Shorthand, so views read close to Android's `stringResource(R.string.x)`.
func L(_ key: String) -> String { Language.string(key) }
func L(_ key: String, _ arguments: CVarArg...) -> String {
    String(format: Language.string(key), locale: Language.effectiveLocale, arguments: arguments)
}
