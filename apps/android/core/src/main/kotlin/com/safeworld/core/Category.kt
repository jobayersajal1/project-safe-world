package com.safeworld.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `packages/core/src/categories.ts`. Keep in sync with that file —
 * it is the source of truth for category ids and labels across platforms.
 *
 * The `@SerialName`s are the wire ids shared with the other platforms, so a
 * persisted `Settings` blob and a `RemoteUpdatePayload` use the same keys the
 * Chrome extension and the iOS app use.
 */
/**
 * The `id`/`SerialName` values are deliberately opaque: they are the keys in
 * the published payload and the bundled resource file names, both visible in an
 * unpacked APK, and `"adult"` would announce a list's contents even though its
 * domains are hashed. The enum constants stay meaningful so the code reads
 * normally, and `CategoryMeta.label` is what the UI shows.
 */
@Serializable
enum class CategoryId(val id: String) {
    @SerialName("list1")
    SCAM("list1"),

    @SerialName("list2")
    GAMBLING("list2"),

    @SerialName("list3")
    ADULT("list3"),

    @SerialName("list4")
    SOCIAL("list4"),

    @SerialName("list5")
    ENTERTAINMENT("list5"),

    @SerialName("list6")
    GAMES("list6"),

    @SerialName("list7")
    DRUGS("list7");

    companion object {
        /** The `CategoryId` with this wire id, or null if unrecognized. */
        fun fromId(raw: String): CategoryId? = entries.firstOrNull { it.id == raw }
    }
}

data class CategoryMeta(
    val id: CategoryId,
    /**
     * Short human label. English only — this module is plain JVM with no access
     * to Android resources, so the app maps ids to localized strings in
     * `ui/CategoryStrings.kt`. This is the fallback and the name used in logs.
     */
    val label: String,
    /** One-line description. Same caveat as [label]. */
    val description: String,
    /** Whether the category is enabled by default on first install. */
    val defaultEnabled: Boolean,
    /**
     * Whether the user may turn this category off.
     *
     * The three protection categories are the reason the app exists — installing
     * it is the decision to block them — so they are forced on (see
     * `SettingsStore.enforceMandatoryCategories`). The opt-in ones are lifestyle
     * choices rather than protection, off until the user asks for them.
     */
    val optional: Boolean,
)

object Categories {
    val all: List<CategoryMeta> = listOf(
        CategoryMeta(
            id = CategoryId.SCAM,
            label = "Scam & Malware",
            description = "Phishing, fraud, and malware-hosting sites.",
            defaultEnabled = true,
            optional = false,
        ),
        CategoryMeta(
            id = CategoryId.DRUGS,
            label = "Drugs & Illegal",
            description = "Drug marketplaces and paraphernalia, plus piracy and warez sites.",
            defaultEnabled = true,
            optional = false,
        ),
        CategoryMeta(
            id = CategoryId.GAMBLING,
            label = "Gambling",
            description = "Casinos, betting, and gambling sites.",
            defaultEnabled = true,
            optional = false,
        ),
        CategoryMeta(
            id = CategoryId.ADULT,
            label = "Adult Content",
            description = "Pornography and explicit adult sites.",
            defaultEnabled = true,
            optional = false,
        ),
        CategoryMeta(
            id = CategoryId.SOCIAL,
            label = "Social Media",
            description = "Facebook, Instagram, X/Twitter, TikTok, and similar feeds.",
            defaultEnabled = false,
            optional = true,
        ),
        CategoryMeta(
            id = CategoryId.ENTERTAINMENT,
            label = "Entertainment",
            description = "YouTube, Netflix, and other streaming and video sites.",
            defaultEnabled = false,
            optional = true,
        ),
        CategoryMeta(
            id = CategoryId.GAMES,
            label = "Games",
            description = "Game portals, browser games, and gaming platforms.",
            defaultEnabled = false,
            optional = true,
        ),
    )

    /** Always on, never shown as a switch. */
    val mandatory: List<CategoryMeta> = all.filter { !it.optional }

    /** Offered as a question ("want to block more?") rather than as a promise. */
    val optional: List<CategoryMeta> = all.filter { it.optional }

    // `all` always has one entry per CategoryId, so this cannot fail.
    fun meta(id: CategoryId): CategoryMeta = all.first { it.id == id }
}
