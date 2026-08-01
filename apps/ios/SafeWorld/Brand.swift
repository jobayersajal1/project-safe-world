import SwiftUI

/// The app's colours, shared with the Android theme and the website.
///
/// **Warm, not cool, and that is deliberate.** Every product in this category is cold blue or
/// teal, because that is what security software is supposed to look like — which is the problem.
/// This app lives on a family's phone, and a parent opening it should feel reassured, not audited.
/// So: warm paper, aged brass, sage.
///
/// Kept here rather than in an asset catalogue so the values sit beside the reasoning, and so they
/// can be read against `apps/android/.../ui/theme/Theme.kt` without opening Xcode.
enum Brand {
    /// Aged brass. Deep enough in light mode to carry white text, light enough in dark mode to
    /// stay legible without glowing.
    static let accent = Color(light: 0x8A6A34, dark: 0xE4C48D)

    /// Sage — the second voice, and the one that carries "protected". Green says safe without
    /// shouting it, which is the tone the whole app is aiming at: no red, no alarm, nothing that
    /// rewards anxiety.
    static let calm = Color(light: 0x5C6B52, dark: 0xBECBB2)
}

private extension Color {
    /// Two hex literals rather than an asset catalogue entry, so the light and dark values are
    /// visible together at the point of definition.
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}
