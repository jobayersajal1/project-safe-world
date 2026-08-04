package com.safeworld.core

import kotlinx.serialization.Serializable

/**
 * Mirrors `packages/core/src/blur.ts`. Keep the two in step by hand.
 *
 * Safe World's other feature is all-or-nothing: an app or a site is reachable or
 * it is not. This is the setting in between — the screen stays usable, and
 * people of the chosen gender are blurred where they appear.
 *
 * Stored under its own key rather than as fields on [Settings]. Kotlin would
 * tolerate the extra fields (every one has a default, so an old blob still
 * decodes), but the Swift `Settings` would not — `Codable` makes a new
 * non-optional property *required*, `loadSettings` decodes with `try?`, and the
 * whole thing silently falls back to defaults. Splitting it here keeps one shape
 * across the ports instead of one platform quietly differing.
 */
@Serializable
data class BlurSettings(
    /** Master switch for this feature alone. */
    val enabled: Boolean = false,
    val target: BlurTarget = BlurTarget.WOMEN,
    /** Blur radius, in density-independent pixels. */
    val strength: Int = 16,
    /**
     * How the reveal gesture works.
     *
     * Revealing *weakens* protection, so moving this off [BlurRevealMode.NEVER]
     * costs the PIN — the same rule every other setting is held to.
     */
    val revealMode: BlurRevealMode = BlurRevealMode.NEVER,
) {
    companion object {
        fun defaults(): BlurSettings = BlurSettings()

        fun withDefaults(stored: BlurSettings?): BlurSettings = stored ?: defaults()
    }
}

/** Who gets blurred. Mirrors `BlurTarget` in `packages/core/src/blur.ts`. */
@Serializable
enum class BlurTarget {
    WOMEN,
    MEN,
    EVERYONE,
    ;

    /** The lowercase wire form the TypeScript and Swift ports use. */
    val id: String get() = name.lowercase()

    companion object {
        fun fromId(id: String): BlurTarget =
            entries.firstOrNull { it.id == id.lowercase() } ?: WOMEN
    }
}

/** Mirrors `BlurRevealMode` in `packages/core/src/blur.ts`. */
@Serializable
enum class BlurRevealMode {
    NEVER,
    TAP,
    ;

    val id: String get() = name.lowercase()
}

/** One face the detector found, reduced to what the verdict actually needs. */
data class FaceObservation(
    val isMale: Boolean,
    /** How sure the classifier was, 0..1. */
    val genderProbability: Float,
)

enum class BlurVerdict { BLUR, CLEAR }

object BlurRules {
    /**
     * Below this the classifier is close enough to a coin flip that its answer
     * carries no information — and an uncertain face is blurred, so this is the
     * threshold that turns "don't know" into "blur".
     *
     * Must stay equal to `GENDER_CONFIDENCE` in `packages/core/src/blur.ts`.
     * The two platforms running the *same* classifier and still disagreeing
     * about the same photo would be worse than either being wrong alone.
     */
    const val GENDER_CONFIDENCE: Float = 0.75f

    /**
     * Whether a region containing these faces should be blurred.
     *
     * **Uncertainty blurs.** A face the classifier is unsure about is treated as
     * if it were the target: a missed blur is the failure the user installed
     * this to prevent, an over-blur is an inconvenience.
     *
     * **So does a region with no face at all** — but that is the caller's
     * decision, not this function's, because the two cases mean opposite
     * things. An *image* with no faces is a picture of nobody and is left
     * alone; a detected *person* with no readable face is someone turned away
     * from the camera, and must be blurred. See `PersonScanner`.
     */
    fun verdict(
        faces: List<FaceObservation>,
        target: BlurTarget,
        confidence: Float = GENDER_CONFIDENCE,
    ): BlurVerdict {
        if (faces.isEmpty()) return BlurVerdict.CLEAR
        if (target == BlurTarget.EVERYONE) return BlurVerdict.BLUR

        val wantMale = target == BlurTarget.MEN
        for (f in faces) {
            if (f.genderProbability < confidence) return BlurVerdict.BLUR
            if (f.isMale == wantMale) return BlurVerdict.BLUR
        }
        return BlurVerdict.CLEAR
    }
}
