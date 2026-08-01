package com.safeworld.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's own colours, instead of Material 3's baseline purple.
 *
 * **The direction is warm, not cool, and that is a deliberate refusal.** Every product in this
 * category — Kahf Guard, Canopy, Bark, Net Nanny — is cold blue or teal, because that is what
 * security software is supposed to look like. Looking like security software is exactly the problem:
 * this app lives on a family's phone, and a parent opening it should feel reassured, not audited.
 *
 * So the palette is **warm paper, aged brass, and sage**. It is taken from the website, which had
 * already committed to a serif headline and an amber accent — the app was the thing that had drifted
 * off-brand into stock purple. The amber has been pulled back from the website's `#e3a542`, which is
 * bright enough to buzz on a large surface; brass at this saturation reads considered rather than
 * loud, and holds up under the long stare the headline card gets.
 *
 * Sage is the second voice, and it carries "protected". Green says safe without shouting it, which
 * is the tone this whole app is trying to hit — no red, no alarm, nothing that rewards anxiety.
 *
 * Deliberately **not** `dynamicColorScheme`. Material You would repaint the app from the user's
 * wallpaper, which is charming for a calculator and wrong here: the one screen that must look
 * deliberate rather than assembled, and identical on every phone, is the one a parent checks to see
 * whether their family is covered.
 *
 * Values are hand-set rather than generated, because the generated tonal ramp puts the containers at
 * a saturation that shouts on a card the size of the headline.
 */
private val LightColors = lightColorScheme(
    // Brass, not gold. Dark enough to carry white text on a switch or a button.
    primary = Color(0xFF8A6A34),
    onPrimary = Color(0xFFFFFFFF),
    // The headline card: warm honey, pale enough to sit under a long sentence in four scripts
    // without tiring the eye.
    primaryContainer = Color(0xFFF2E4C9),
    onPrimaryContainer = Color(0xFF2C1F06),
    // Sage. This is "protected" — quiet, not congratulatory.
    secondary = Color(0xFF5C6B52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE8D5),
    onSecondaryContainer = Color(0xFF192013),
    tertiary = Color(0xFF7A5A54),
    onTertiary = Color(0xFFFFFFFF),
    // Paper, not white. A screen of pure #FFFFFF is the single most tiring thing a phone can show,
    // and the whole point of this palette is that it can be looked at for a while.
    background = Color(0xFFF9F6F0),
    onBackground = Color(0xFF1E1A14),
    surface = Color(0xFFF9F6F0),
    onSurface = Color(0xFF1E1A14),
    // Every ordinary card: a hair of warm sand off the background, so cards read as raised by shape
    // rather than by a hard edge.
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF4E463A),
    outline = Color(0xFF80776A),
    outlineVariant = Color(0xFFD1C8B9),
    error = Color(0xFF9B4034),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DDD7),
    onErrorContainer = Color(0xFF3B0D07),
    // The container roles are what `Card`, `NavigationBar` and every elevated surface actually
    // read — *not* `surfaceVariant`. Leaving them unset is why the first cut of this theme still
    // had lavender cards sitting on warm paper: Material fell back to its baseline purple ramp
    // underneath a palette that had been changed everywhere else.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1E8),
    surfaceContainer = Color(0xFFF0EBE0),
    surfaceContainerHigh = Color(0xFFEAE4D7),
    surfaceContainerHighest = Color(0xFFE4DDCE),
    surfaceDim = Color(0xFFDFD8C9),
    surfaceBright = Color(0xFFF9F6F0),
    inverseSurface = Color(0xFF34302A),
    inverseOnSurface = Color(0xFFF5F1E8),
    inversePrimary = Color(0xFFE4C48D),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE4C48D),
    onPrimary = Color(0xFF422F0B),
    primaryContainer = Color(0xFF5B451D),
    onPrimaryContainer = Color(0xFFFFDFB0),
    secondary = Color(0xFFBECBB2),
    onSecondary = Color(0xFF293422),
    secondaryContainer = Color(0xFF3F4B37),
    onSecondaryContainer = Color(0xFFDAE7CD),
    tertiary = Color(0xFFE6BDB4),
    onTertiary = Color(0xFF442925),
    // Warm near-black rather than blue-grey, so the dark theme is plainly the same app after sunset
    // instead of a colder cousin of it.
    background = Color(0xFF16130E),
    onBackground = Color(0xFFEAE2D6),
    surface = Color(0xFF16130E),
    onSurface = Color(0xFFEAE2D6),
    surfaceVariant = Color(0xFF3C362C),
    onSurfaceVariant = Color(0xFFCFC6B6),
    outline = Color(0xFF988E80),
    outlineVariant = Color(0xFF3C362C),
    error = Color(0xFFEBB4A8),
    onError = Color(0xFF5C1A11),
    errorContainer = Color(0xFF7A2C20),
    onErrorContainer = Color(0xFFFFDAD3),
    surfaceContainerLowest = Color(0xFF100E0A),
    surfaceContainerLow = Color(0xFF1E1A14),
    surfaceContainer = Color(0xFF231F18),
    surfaceContainerHigh = Color(0xFF2E2921),
    surfaceContainerHighest = Color(0xFF39332B),
    surfaceDim = Color(0xFF16130E),
    surfaceBright = Color(0xFF3D372E),
    inverseSurface = Color(0xFFEAE2D6),
    inverseOnSurface = Color(0xFF34302A),
    inversePrimary = Color(0xFF8A6A34),
    scrim = Color(0xFF000000),
)

/**
 * Rounder than Material's defaults (8/12/16).
 *
 * Softer corners are most of what separates "considered" from "stock Android" at a glance, and they
 * cost nothing. `large` is what the headline card uses.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/**
 * Only the styles this app actually renders are touched.
 *
 * The headline gets more line height and slight negative tracking: Material's default is tuned for
 * short labels, and ours is a full sentence that wraps to two lines in every language. Body text
 * gets extra line height because two of the four languages — Bengali and Arabic — have taller
 * glyphs and diacritics that collide at the default.
 */
private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            lineHeight = 36.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        // Section headings ("Want to block more?", "Block apps"). Slightly tracked out and
        // semi-bold, so they separate the page without needing a rule or a heavier weight.
        titleSmall = titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        ),
        bodyLarge = bodyLarge.copy(lineHeight = 25.sp),
        bodySmall = bodySmall.copy(lineHeight = 20.sp),
    )
}

@Composable
fun SafeWorldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
